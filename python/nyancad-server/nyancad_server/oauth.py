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
    3. Stores all OAuth state in CouchDB (oauth_clients database) for multi-worker support
    4. Supports PKCE for secure authorization code flow
    """

    async def _get_user_doc(self, username: str) -> dict | None:
        """Fetch user document from CouchDB _users database."""
        user_id = f"org.couchdb.user:{username}"
        return await self._couchdb_get(user_id, database="_users")

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

        logger.debug(f"Updating user doc {user_id} with: {user_doc}")
        success = await self._couchdb_put(user_id, user_doc, database="_users")

        if not success:
            logger.error(f"CouchDB user update failed for {user_id}")
            logger.error(f"Document that failed: {user_doc}")

        return success

    async def _couchdb_get(self, doc_id: str, database: str = "oauth_clients") -> dict | None:
        """Generic CouchDB GET operation."""
        async with httpx.AsyncClient() as client:
            try:
                response = await client.get(
                    f"{COUCHDB_URL}/{database}/{doc_id}",
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )
                if response.status_code != 200:
                    return None
                return response.json()
            except Exception as e:
                logger.error(f"CouchDB GET {doc_id} failed: {e}")
                return None

    async def _couchdb_put(self, doc_id: str, doc: dict, database: str = "oauth_clients") -> bool:
        """Generic CouchDB PUT operation."""
        async with httpx.AsyncClient() as client:
            try:
                response = await client.put(
                    f"{COUCHDB_URL}/{database}/{doc_id}",
                    json=doc,
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )
                if response.status_code not in (200, 201, 202):
                    logger.error(f"CouchDB PUT {doc_id} failed: {response.status_code} - {response.text}")
                    return False
                logger.debug(f"CouchDB PUT {doc_id} succeeded")
                return True
            except Exception as e:
                logger.error(f"CouchDB PUT {doc_id} error: {e}")
                return False

    async def _couchdb_delete(self, doc_id: str, database: str = "oauth_clients") -> bool:
        """Generic CouchDB DELETE with atomic _rev handling."""
        doc = await self._couchdb_get(doc_id, database)
        if not doc:
            return True  # Already deleted

        async with httpx.AsyncClient() as client:
            try:
                response = await client.delete(
                    f"{COUCHDB_URL}/{database}/{doc_id}?rev={doc['_rev']}",
                    auth=(COUCHDB_ADMIN_USER, COUCHDB_ADMIN_PASS)
                )
                if response.status_code in (200, 404, 409):  # 409 = race condition
                    return True
                logger.error(f"CouchDB DELETE {doc_id} failed: {response.status_code}")
                return False
            except Exception as e:
                logger.error(f"CouchDB DELETE {doc_id} error: {e}")
                return False

    async def _store_state(self, state: str, params: dict) -> bool:
        """Store OAuth state to oauth_clients database."""
        doc_id = f"state:{state}"
        doc = {
            "_id": doc_id,
            "expires_at": time.time() + 300,  # 5 minutes
            **params,
        }
        return await self._couchdb_put(doc_id, doc)

    async def _get_state(self, state: str) -> dict | None:
        """Get OAuth state from oauth_clients database."""
        doc_id = f"state:{state}"
        doc = await self._couchdb_get(doc_id)

        if not doc:
            return None

        # Check expiration
        if doc.get("expires_at", 0) < time.time():
            logger.info(f"OAuth state {state} expired")
            await self._delete_state(state)
            return None

        # Return params without internal fields
        result = doc.copy()
        result.pop("_id", None)
        result.pop("_rev", None)
        result.pop("expires_at", None)
        return result

    async def _delete_state(self, state: str) -> bool:
        """Delete OAuth state from oauth_clients database."""
        return await self._couchdb_delete(f"state:{state}")

    async def _store_auth_code(self, code: str, auth_code: AuthorizationCode, user_data: dict) -> bool:
        """Store authorization code to oauth_clients database."""
        doc_id = f"code:{code}"

        # Serialize AuthorizationCode using Pydantic
        auth_code_dict = auth_code.model_dump(mode='json', exclude_none=True)

        doc = {
            "_id": doc_id,
            "auth_code": auth_code_dict,
            "user_data": user_data,
            "expires_at": time.time() + 300,  # 5 minutes
        }
        return await self._couchdb_put(doc_id, doc)

    async def _get_auth_code(self, code: str) -> tuple[AuthorizationCode, dict] | None:
        """Get authorization code from oauth_clients database."""
        doc_id = f"code:{code}"
        doc = await self._couchdb_get(doc_id)

        if not doc:
            return None

        # Check expiration
        if doc.get("expires_at", 0) < time.time():
            logger.info(f"Authorization code {code} expired")
            await self._delete_auth_code(code)
            return None

        # Reconstruct AuthorizationCode object using Pydantic
        auth_code = AuthorizationCode.model_validate(doc["auth_code"])
        return auth_code, doc["user_data"]

    async def _delete_auth_code(self, code: str) -> bool:
        """Delete authorization code atomically (single-use protection)."""
        return await self._couchdb_delete(f"code:{code}")

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        """Get OAuth client from CouchDB oauth_clients database."""
        doc_id = f"client:{client_id}"
        doc = await self._couchdb_get(doc_id)

        if not doc:
            return None

        client_data = doc.get("client_info")
        if not client_data:
            return None

        return OAuthClientInformationFull(**client_data)

    async def register_client(self, client_info: OAuthClientInformationFull):
        """Register OAuth client - persist to CouchDB oauth_clients database."""
        if not client_info.client_id:
            raise ValueError("No client_id provided")

        doc_id = f"client:{client_info.client_id}"
        client_data = client_info.model_dump(mode='json', exclude_none=True)

        # Try to fetch existing doc to get _rev
        existing_doc = await self._couchdb_get(doc_id)

        doc = {"_id": doc_id, "client_info": client_data}
        if existing_doc:
            doc["_rev"] = existing_doc["_rev"]

        # Store/update document
        success = await self._couchdb_put(doc_id, doc)
        if not success:
            logger.error(f"Failed to register client {client_info.client_id}")

    async def authorize(self, client: OAuthClientInformationFull, params: AuthorizationParams) -> str:
        """Generate authorization URL that redirects to login page."""
        state = params.state or secrets.token_hex(16)

        # Store authorization parameters in CouchDB for use after login
        # Use Pydantic model_dump, exclude state (it's the key), add client_id
        state_data = params.model_dump(mode='json', exclude={'state'}, exclude_none=True)
        state_data["client_id"] = client.client_id
        await self._store_state(state, state_data)

        # Redirect to auth.cljs page with state parameter
        return f"{SERVER_URL}/auth/?state={state}"

    async def load_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: str
    ) -> AuthorizationCode | None:
        """Load authorization code for validation."""
        result = await self._get_auth_code(authorization_code)
        if not result:
            return None
        auth_code, _ = result
        return auth_code

    async def exchange_authorization_code(
        self,
        client: OAuthClientInformationFull,
        authorization_code: AuthorizationCode,
    ) -> OAuthToken:
        """Exchange authorization code for access token."""
        if not authorization_code.code:
            raise HTTPException(400, "Invalid authorization code")

        # Get authorization code and user data from CouchDB
        result = await self._get_auth_code(authorization_code.code)
        if not result:
            raise HTTPException(400, "Invalid or expired authorization code")

        auth_code_stored, user_data = result
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
        # Fetch user doc to append to tokens array (support multiple devices)
        user_doc = await self._get_user_doc(username)
        if not user_doc:
            raise HTTPException(400, "User not found")

        oauth_data = user_doc.get("oauth", {})
        tokens = oauth_data.get("tokens", [])

        # Add new token to array
        tokens.append({
            "jti": refresh_jti,
            "expires_at": refresh_expires_at,
            "issued_at": now,
            "client_id": client.client_id or "",
        })

        # Clean up expired tokens (keep array manageable)
        tokens = [t for t in tokens if t.get("expires_at", 0) > now]

        await self._update_user_doc(username, {
            "oauth": {
                "tokens": tokens,
            }
        }, user_doc=user_doc)

        # Clean up used authorization code (single-use)
        await self._delete_auth_code(authorization_code.code)

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
            tokens = oauth_data.get("tokens", [])

            # Find matching token by JTI
            matching_token = None
            for token_data in tokens:
                if token_data.get("jti") == jti:
                    matching_token = token_data
                    break

            # Token is revoked if JTI not found in tokens array
            if not matching_token:
                logger.info(f"Refresh token revoked for user {username}")
                return None

            return RefreshToken(
                token=refresh_token,
                client_id=matching_token.get("client_id", ""),
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

            # Update user document: remove old token and add new one (rotation)
            # Pass user_doc to avoid extra GET request
            oauth_data = user_doc.get("oauth", {})
            tokens = oauth_data.get("tokens", [])

            # Get old JTI from the refresh token being rotated
            old_payload = jwt.decode(
                refresh_token.token,
                options={"verify_signature": False}
            )
            old_jti = old_payload.get("jti")

            # Remove old token and add new one (rotation invalidates old token)
            tokens = [t for t in tokens if t.get("jti") != old_jti]
            tokens.append({
                "jti": new_refresh_jti,
                "expires_at": refresh_expires_at,
                "issued_at": now,
                "client_id": client.client_id or "",
            })

            # Clean up expired tokens
            tokens = [t for t in tokens if t.get("expires_at", 0) > now]

            success = await self._update_user_doc(
                username,
                {
                    "oauth": {
                        "tokens": tokens,
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
                jti = payload.get("jti")
                if username and jti:
                    # Remove specific token from tokens array
                    user_doc = await self._get_user_doc(username)
                    if user_doc:
                        oauth_data = user_doc.get("oauth", {})
                        tokens = oauth_data.get("tokens", [])
                        tokens = [t for t in tokens if t.get("jti") != jti]
                        await self._update_user_doc(username, {"oauth": {"tokens": tokens}}, user_doc=user_doc)
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

            # Get state mapping from CouchDB
            auth_params = await self._get_state(state)
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

            # Store authorization code and user data in CouchDB
            success = await self._store_auth_code(code, auth_code, {
                "username": authenticated_username,
                "roles": roles,
            })

            if not success:
                return JSONResponse(
                    {"error": "Failed to create authorization code"},
                    status_code=500
                )

            # Clean up used state mapping
            await self._delete_state(state)

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
