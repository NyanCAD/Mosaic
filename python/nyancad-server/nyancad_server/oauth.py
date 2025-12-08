"""OAuth 2.1 Authorization Server for NyanCAD MCP.

This module provides OAuth authorization endpoints that integrate with CouchDB for
user authentication and issue JWT tokens that can be validated by both the MCP server
and CouchDB (using shared HMAC secret).
"""

import logging
import secrets
import time
import uuid
from typing import Any

import httpx
import jwt
from pydantic import AnyHttpUrl, AnyUrl
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from starlette.routing import Route

from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    OAuthAuthorizationServerProvider,
    RefreshToken,
    construct_redirect_uri,
)
from mcp.server.auth.routes import create_auth_routes, cors_middleware
from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken

from .config import JWT_SECRET, SERVER_URL, COUCHDB_URL, COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS

logger = logging.getLogger(__name__)


class JWTTokenVerifier:
    """Verifies JWT tokens signed with shared HMAC secret.

    This verifier validates JWTs that were issued by the OAuth Authorization Server.
    The same JWTs can be used with CouchDB when it's configured with the same HMAC secret.
    """

    async def verify_token(self, token: str) -> AccessToken | None:
        """Verify JWT and return AccessToken.

        Args:
            token: JWT token string from Authorization: Bearer header

        Returns:
            AccessToken if valid with username, scopes, and expiry
            None if token is invalid, expired, or malformed
        """
        if not JWT_SECRET:
            logger.error("JWT_SECRET environment variable not set")
            return None

        try:
            # Decode and validate JWT
            payload = jwt.decode(
                token,
                JWT_SECRET,
                algorithms=["HS256"],
                audience=COUCHDB_URL,
                options={"require": ["sub", "exp", "iat"]}
            )

            # Extract claims
            username = payload.get("sub")
            roles = payload.get("_couchdb.roles", [])
            exp = payload.get("exp")

            if not username:
                logger.warning("Token verification failed: No username in token")
                return None

            # Convert CouchDB roles to MCP scopes
            # Format: "role:admin", "role:_admin", etc.
            scopes = [f"role:{role}" for role in roles] if roles else ["user"]

            return AccessToken(
                token=token,
                client_id=username,  # Use username as client identifier
                scopes=scopes,
                expires_at=exp,
            )

        except jwt.ExpiredSignatureError:
            logger.warning("Token verification failed: Token expired")
            return None
        except jwt.InvalidAudienceError:
            logger.warning(f"Token verification failed: Invalid audience (expected {COUCHDB_URL})")
            return None
        except jwt.InvalidTokenError as e:
            logger.warning(f"Token verification failed: {e}")
            return None
        except Exception as e:
            logger.error(f"Token verification error: {e}")
            return None


class CouchDBOAuthProvider(OAuthAuthorizationServerProvider[AuthorizationCode, RefreshToken, AccessToken]):
    """OAuth provider that integrates with CouchDB for authentication.

    This provider:
    1. Validates user credentials against CouchDB
    2. Issues JWT tokens signed with shared HMAC secret
    3. Maintains in-memory storage for OAuth clients and authorization codes
    4. Supports PKCE for secure authorization code flow
    """

    def __init__(self):
        self.clients: dict[str, OAuthClientInformationFull] = {}
        self.auth_codes: dict[str, AuthorizationCode] = {}
        self.state_mapping: dict[str, dict[str, Any]] = {}
        self.user_data: dict[str, dict[str, Any]] = {}  # Maps authorization code to user data
        # Removed in-memory token storage - tokens now persisted in CouchDB

    async def _get_user_doc(self, username: str) -> dict | None:
        """Fetch user document from CouchDB _users database."""
        user_id = f"org.couchdb.user:{username}"
        async with httpx.AsyncClient() as client:
            try:
                response = await client.get(
                    f"{COUCHDB_URL}/_users/{user_id}",
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )
                if response.status_code == 200:
                    return response.json()
            except Exception as e:
                logger.error(f"Error fetching user doc: {e}")
        return None

    async def _update_user_doc(self, username: str, updates: dict, user_doc: dict | None = None) -> bool:
        """Update user document in CouchDB _users database.

        Args:
            username: Username to update
            updates: Dictionary of fields to update
            user_doc: Optional existing user doc (to avoid extra GET)
        """
        if not user_doc:
            user_doc = await self._get_user_doc(username)
            if not user_doc:
                return False

        user_doc.update(updates)
        user_id = user_doc["_id"]

        async with httpx.AsyncClient() as client:
            try:
                response = await client.put(
                    f"{COUCHDB_URL}/_users/{user_id}",
                    json=user_doc,
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )
                return response.status_code in (200, 201)
            except Exception as e:
                logger.error(f"Error updating user doc: {e}")
                return False

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        """Get OAuth client from CouchDB oauth_clients database."""
        async with httpx.AsyncClient() as client:
            try:
                response = await client.get(
                    f"{COUCHDB_URL}/oauth_clients/{client_id}",
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )

                if response.status_code != 200:
                    return None

                doc = response.json()
                client_data = doc.get("client_info")
                if not client_data:
                    return None

                # Reconstruct OAuthClientInformationFull
                return OAuthClientInformationFull(**client_data)

            except Exception as e:
                logger.error(f"Error fetching client: {e}")
                return None

    async def register_client(self, client_info: OAuthClientInformationFull):
        """Register OAuth client - persist to CouchDB oauth_clients database."""
        if not client_info.client_id:
            raise ValueError("No client_id provided")

        # Persist to CouchDB oauth_clients database
        client_data = client_info.model_dump(mode='json', exclude_none=True)

        async with httpx.AsyncClient() as client:
            try:
                doc_id = client_info.client_id

                # Try to fetch existing doc to get _rev
                response = await client.get(
                    f"{COUCHDB_URL}/oauth_clients/{doc_id}",
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )

                doc = {"_id": doc_id, "client_info": client_data}
                if response.status_code == 200:
                    existing_doc = response.json()
                    doc["_rev"] = existing_doc["_rev"]

                # Store/update document
                response = await client.put(
                    f"{COUCHDB_URL}/oauth_clients/{doc_id}",
                    json=doc,
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )

                if response.status_code not in (200, 201):
                    logger.error(f"Failed to store client: {response.text}")

            except Exception as e:
                logger.error(f"Error storing client: {e}")

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        """Generate authorization URL that redirects to login page."""
        state = params.state or secrets.token_hex(16)

        # Store authorization parameters for use after login
        self.state_mapping[state] = {
            "redirect_uri": str(params.redirect_uri),
            "code_challenge": params.code_challenge,
            "redirect_uri_provided_explicitly": params.redirect_uri_provided_explicitly,
            "client_id": client.client_id,
            "resource": params.resource,
            "scopes": params.scopes,
        }

        # Redirect to auth.cljs page with state parameter
        return f"{SERVER_URL}/auth/?state={state}"

    async def load_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: str
    ) -> AuthorizationCode | None:
        """Load authorization code for validation."""
        return self.auth_codes.get(authorization_code)

    async def exchange_authorization_code(
        self,
        client: OAuthClientInformationFull,
        authorization_code: AuthorizationCode,
    ) -> OAuthToken:
        """Exchange authorization code for access token."""
        if not authorization_code.code:
            raise HTTPException(400, "Invalid authorization code")

        if authorization_code.code not in self.auth_codes:
            raise HTTPException(400, "Invalid or expired authorization code")

        # Get user data for this authorization code
        user_data = self.user_data.get(authorization_code.code)
        if not user_data:
            raise HTTPException(400, "User data not found for authorization code")

        username = user_data["username"]
        roles = user_data["roles"]

        # Validate PKCE (code_challenge is validated by the framework before calling this)

        # Generate JWT access token using PyJWT
        now = int(time.time())
        payload = {
            "sub": username,
            "iss": SERVER_URL,
            "aud": COUCHDB_URL,
            "_couchdb.roles": roles,
            "exp": now + 900,  # 15 minutes expiry (was 3600)
            "iat": now,
            "jti": str(uuid.uuid4()),
        }
        access_token_str = jwt.encode(payload, JWT_SECRET, algorithm="HS256")

        # Generate JWT refresh token (contains username, expiry)
        # Note: Different audience than access tokens to prevent misuse with CouchDB
        refresh_expires_at = now + 604800  # 7 days
        refresh_jti = str(uuid.uuid4())  # Unique token ID for revocation
        refresh_payload = {
            "sub": username,
            "iss": SERVER_URL,
            "aud": f"{SERVER_URL}/token",  # Refresh tokens only valid for token endpoint
            "exp": refresh_expires_at,
            "iat": now,
            "jti": refresh_jti,
            "type": "refresh",  # Distinguish from access tokens
        }
        refresh_token_str = jwt.encode(refresh_payload, JWT_SECRET, algorithm="HS256")

        # Store refresh token JTI in user doc for revocation tracking
        await self._update_user_doc(username, {
            "oauth": {
                "refresh_jti": refresh_jti,  # Store JTI, not full token
                "refresh_expires_at": refresh_expires_at,
                "issued_at": now,
                "client_id": client.client_id or "",
            }
        })

        # Clean up used authorization code and user data
        del self.auth_codes[authorization_code.code]
        if authorization_code.code in self.user_data:
            del self.user_data[authorization_code.code]

        return OAuthToken(
            access_token=access_token_str,
            token_type="Bearer",
            expires_in=900,  # (was 3600)
            scope=" ".join(authorization_code.scopes),
            refresh_token=refresh_token_str,
        )

    async def load_refresh_token(
        self, client: OAuthClientInformationFull, refresh_token: str
    ) -> RefreshToken | None:
        """Load and validate JWT refresh token."""
        try:
            # Decode JWT to extract username and validate
            # Refresh tokens have different audience than access tokens
            payload = jwt.decode(
                refresh_token,
                JWT_SECRET,
                algorithms=["HS256"],
                audience=f"{SERVER_URL}/token",
                options={"require": ["sub", "exp", "iat", "jti", "type"]}
            )

            # Verify it's a refresh token (not access token)
            if payload.get("type") != "refresh":
                logger.warning("Token type mismatch: expected refresh token")
                return None

            username = payload.get("sub")
            jti = payload.get("jti")
            expires_at = payload.get("exp")

            if not username or not jti:
                return None

            # Check if token was revoked by looking up JTI in user doc
            user_doc = await self._get_user_doc(username)
            if not user_doc:
                return None

            oauth_data = user_doc.get("oauth", {})
            stored_jti = oauth_data.get("refresh_jti")

            # Token is revoked if JTI doesn't match (rotation invalidates old tokens)
            if stored_jti != jti:
                logger.info(f"Refresh token revoked for user {username}")
                return None

            return RefreshToken(
                token=refresh_token,
                client_id=oauth_data.get("client_id", ""),
                scopes=[],  # Scopes from original authorization
                expires_at=expires_at,
            )

        except jwt.ExpiredSignatureError:
            logger.warning("Refresh token expired")
            return None
        except jwt.InvalidTokenError as e:
            logger.warning(f"Invalid refresh token: {e}")
            return None
        except Exception as e:
            logger.error(f"Error loading refresh token: {e}")
            return None

    async def exchange_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: RefreshToken,
        scopes: list[str],
    ) -> OAuthToken:
        """Exchange refresh token for new tokens (rotation) - stored in CouchDB."""
        if not refresh_token.token:
            raise HTTPException(400, "Invalid refresh token")

        try:
            # Decode JWT refresh token to extract username
            # Refresh tokens have different audience than access tokens
            payload = jwt.decode(
                refresh_token.token,
                JWT_SECRET,
                algorithms=["HS256"],
                audience=f"{SERVER_URL}/token",
                options={"require": ["sub", "type"]}
            )

            if payload.get("type") != "refresh":
                raise HTTPException(400, "Invalid token type")

            username = payload.get("sub")
            if not username:
                raise HTTPException(400, "Invalid refresh token")

            # Get user document for roles
            user_doc = await self._get_user_doc(username)
            if not user_doc:
                raise HTTPException(400, "User not found")

            roles = user_doc.get("roles", [])

            # Generate new access token (15 min)
            now = int(time.time())
            access_payload = {
                "sub": username,
                "iss": SERVER_URL,
                "aud": COUCHDB_URL,
                "_couchdb.roles": roles,
                "exp": now + 900,  # 15 minutes
                "iat": now,
                "jti": str(uuid.uuid4()),
            }
            access_token_str = jwt.encode(access_payload, JWT_SECRET, algorithm="HS256")

            # Generate new JWT refresh token (rotation for security)
            # Different audience prevents use as access token
            refresh_expires_at = now + 604800  # 7 days
            new_refresh_jti = str(uuid.uuid4())
            refresh_payload = {
                "sub": username,
                "iss": SERVER_URL,
                "aud": f"{SERVER_URL}/token",  # Only valid for token endpoint
                "exp": refresh_expires_at,
                "iat": now,
                "jti": new_refresh_jti,
                "type": "refresh",
            }
            new_refresh_token = jwt.encode(refresh_payload, JWT_SECRET, algorithm="HS256")

            # Update user document with new refresh JTI (invalidates old one)
            # Pass user_doc to avoid extra GET request
            success = await self._update_user_doc(
                username,
                {
                    "oauth": {
                        "refresh_jti": new_refresh_jti,
                        "refresh_expires_at": refresh_expires_at,
                        "issued_at": now,
                        "client_id": client.client_id or "",
                    }
                },
                user_doc=user_doc  # Reuse already-fetched doc
            )

            if not success:
                raise HTTPException(500, "Failed to update refresh token")

            return OAuthToken(
                access_token=access_token_str,
                token_type="Bearer",
                expires_in=900,
                scope=" ".join(scopes),
                refresh_token=new_refresh_token,
            )

        except HTTPException:
            raise
        except Exception as e:
            logger.error(f"Error exchanging refresh token: {e}")
            raise HTTPException(500, "Internal server error")

    async def load_access_token(self, token: str) -> AccessToken | None:
        """Load and validate access token (for introspection) by decoding JWT."""
        try:
            # Decode and validate JWT
            payload = jwt.decode(
                token,
                JWT_SECRET,
                algorithms=["HS256"],
                audience=COUCHDB_URL,
                options={"require": ["sub", "exp", "iat"]}
            )

            username = payload.get("sub")
            roles = payload.get("_couchdb.roles", [])
            exp = payload.get("exp")

            if not username:
                return None

            # Convert CouchDB roles to MCP scopes
            scopes = [f"role:{role}" for role in roles] if roles else ["user"]

            return AccessToken(
                token=token,
                client_id=username,
                scopes=scopes,
                expires_at=exp,
            )

        except (jwt.ExpiredSignatureError, jwt.InvalidTokenError):
            return None
        except Exception as e:
            logger.error(f"Token introspection error: {e}")
            return None

    async def revoke_token(self, token: str, token_type_hint: str | None = None) -> None:  # type: ignore
        """Revoke a token.

        For refresh tokens: decode JWT to get username, then clear from user document.
        For access tokens: JWTs cannot be revoked (stateless), but they expire after 15 minutes.
        """
        try:
            # Try to decode as refresh token
            payload = jwt.decode(
                token,
                JWT_SECRET,
                algorithms=["HS256"],
                audience=f"{SERVER_URL}/token",
                options={"verify_exp": False}  # Allow revoking expired tokens
            )

            if payload.get("type") == "refresh":
                username = payload.get("sub")
                if username:
                    # Clear OAuth data from user document
                    await self._update_user_doc(username, {"oauth": {}})
                    logger.info(f"Revoked refresh token for user {username}")
                    return

            # Not a refresh token or no username - try as access token
            # Access tokens can't be revoked (stateless), just log
            logger.info("Cannot revoke access token (stateless JWT)")

        except jwt.InvalidTokenError:
            # Invalid token format, nothing to revoke
            logger.warning("Cannot revoke invalid token")
        except Exception as e:
            logger.error(f"Error revoking token: {e}")

    async def validate_credentials(
        self,
        request: Request,
        username: str | None = None,
        password: str | None = None
    ) -> tuple[bool, str, list[str]]:
        """Validate credentials against CouchDB and return user info.

        Supports credential-based auth OR session cookie auth.
        Returns: (valid, username, roles)
        """
        async with httpx.AsyncClient() as client:
            try:
                # Conditionally prepare request parameters
                if username and password:
                    # Credential-based: POST with form data
                    method = "POST"
                    url = f"{COUCHDB_URL}/_session"
                    kwargs = {
                        "data": {"name": username, "password": password},
                        "headers": {"Content-Type": "application/x-www-form-urlencoded"}
                    }
                else:
                    # Session-based: GET with cookie
                    session_cookie = request.cookies.get("AuthSession")
                    if not session_cookie:
                        return False, "", []

                    method = "GET"
                    url = f"{COUCHDB_URL}/_session"
                    kwargs = {"cookies": {"AuthSession": session_cookie}}

                # Single CouchDB call
                response = await client.request(method, url, **kwargs)

                if response.status_code != 200:
                    return False, "", []

                data = response.json()

                # Handle both response formats:
                # POST /_session: {"ok": true, "name": "username", "roles": [...]}
                # GET /_session: {"ok": true, "userCtx": {"name": "username", "roles": [...]}}
                if "userCtx" in data:
                    # GET format (session check)
                    user_ctx = data["userCtx"]
                    authenticated_username = user_ctx.get("name")
                    roles = user_ctx.get("roles", [])
                else:
                    # POST format (login)
                    authenticated_username = data.get("name")
                    roles = data.get("roles", [])

                if not authenticated_username:
                    return False, "", []

                return True, authenticated_username, roles

            except Exception as e:
                logger.error(f"CouchDB authentication error: {e}")
                return False, "", []

    async def handle_oauth_login(self, request: Request) -> Response:
        """Handle OAuth login via JSON API.

        Supports credential-based or session cookie authentication.
        """
        try:
            body = await request.json()
            state = body.get("state")
            username = body.get("username")  # Optional
            password = body.get("password")  # Optional

            if not state:
                return JSONResponse(
                    {"error": "Missing state parameter"},
                    status_code=400
                )

            auth_params = self.state_mapping.get(state)
            if not auth_params:
                return JSONResponse(
                    {"error": "Invalid or expired state parameter"},
                    status_code=400
                )

            # Validate credentials (handles both modes)
            valid, authenticated_username, roles = await self.validate_credentials(
                request, username, password
            )

            if not valid:
                if username and password:
                    error_msg = "Invalid username or password"
                else:
                    error_msg = "No active session. Please login first."
                return JSONResponse({"error": error_msg}, status_code=401)

            # Generate authorization code
            code = f"nyancad_{secrets.token_hex(16)}"

            auth_code = AuthorizationCode(
                code=code,
                client_id=auth_params["client_id"],
                redirect_uri=AnyUrl(auth_params["redirect_uri"]),  # Use AnyUrl to support custom schemes like vscode://
                redirect_uri_provided_explicitly=auth_params["redirect_uri_provided_explicitly"],
                expires_at=time.time() + 300,
                scopes=auth_params.get("scopes") or ["user"],  # Handle None values
                code_challenge=auth_params.get("code_challenge"),
                resource=auth_params.get("resource"),
            )

            self.auth_codes[code] = auth_code
            self.user_data[code] = {
                "username": authenticated_username,
                "roles": roles,
            }

            del self.state_mapping[state]

            redirect_uri = construct_redirect_uri(
                auth_params["redirect_uri"],
                code=code,
                state=state,
            )

            return JSONResponse({"redirect_url": redirect_uri})

        except Exception as e:
            logger.error(f"OAuth login error: {e}")
            return JSONResponse(
                {"error": "Internal server error"},
                status_code=500
            )


# Create global provider instance
oauth_provider = CouchDBOAuthProvider()


# Create OAuth routes using MCP SDK
def create_oauth_routes() -> list[Route]:
    """Create all OAuth routes including standard OAuth endpoints and custom login endpoints."""

    # OAuth settings for MCP (standard OAuth paths at root level)
    auth_settings = AuthSettings(
        issuer_url=AnyHttpUrl(SERVER_URL),
        client_registration_options=ClientRegistrationOptions(
            enabled=True,
            valid_scopes=["user"],
            default_scopes=["user"],
        ),
        required_scopes=["user"],
        resource_server_url=AnyHttpUrl(f"{SERVER_URL}/ai"),
    )

    # Create standard OAuth routes (register, authorize, token, metadata)
    routes = create_auth_routes(
        provider=oauth_provider,
        issuer_url=auth_settings.issuer_url,
        service_documentation_url=auth_settings.service_documentation_url,
        client_registration_options=auth_settings.client_registration_options,
        revocation_options=auth_settings.revocation_options,
    )

    # Add JSON OAuth login endpoint (POST) - used by auth.cljs
    async def oauth_login_handler(request: Request) -> Response:
        """Handle OAuth login via JSON API (used by auth.cljs)."""
        return await oauth_provider.handle_oauth_login(request)

    routes.append(Route("/oauth/login", endpoint=oauth_login_handler, methods=["POST"]))

    # Add protected resource metadata (RFC 9728)
    async def protected_resource_handler(request: Request) -> Response:
        """Serve protected resource metadata."""
        metadata = {
            "resource": f"{SERVER_URL}/ai",
            "authorization_servers": [SERVER_URL],
            "bearer_methods_supported": ["header"],
            "resource_documentation": f"{SERVER_URL}/",
        }
        return JSONResponse(metadata)

    routes.append(
        Route(
            "/.well-known/oauth-protected-resource",
            endpoint=cors_middleware(protected_resource_handler, ["GET", "OPTIONS"]),
            methods=["GET", "OPTIONS"],
        )
    )

    return routes
