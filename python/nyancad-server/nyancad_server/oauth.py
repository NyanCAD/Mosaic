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
from pydantic import AnyHttpUrl
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

from .config import JWT_SECRET, SERVER_URL, COUCHDB_URL

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
        self.tokens: dict[str, AccessToken] = {}
        self.state_mapping: dict[str, dict[str, Any]] = {}
        self.user_data: dict[str, dict[str, Any]] = {}  # Maps code/token to user data

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        """Get OAuth client information by client_id."""
        return self.clients.get(client_id)

    async def register_client(self, client_info: OAuthClientInformationFull):
        """Register a new OAuth client (Dynamic Client Registration - RFC 7591)."""
        if not client_info.client_id:
            raise ValueError("No client_id provided")
        self.clients[client_info.client_id] = client_info

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
            "exp": now + 3600,  # 1 hour expiry
            "iat": now,
            "jti": str(uuid.uuid4()),
        }
        access_token_str = jwt.encode(payload, JWT_SECRET, algorithm="HS256")

        # Store access token for introspection
        self.tokens[access_token_str] = AccessToken(
            token=access_token_str,
            client_id=client.client_id or "",
            scopes=authorization_code.scopes,
            expires_at=now + 3600,
            resource=authorization_code.resource,
        )

        # Clean up used authorization code and user data
        del self.auth_codes[authorization_code.code]
        if authorization_code.code in self.user_data:
            del self.user_data[authorization_code.code]

        return OAuthToken(
            access_token=access_token_str,
            token_type="Bearer",
            expires_in=3600,
            scope=" ".join(authorization_code.scopes),
        )

    async def load_refresh_token(
        self, client: OAuthClientInformationFull, refresh_token: str
    ) -> RefreshToken | None:
        """Load refresh token (not implemented - using short-lived tokens)."""
        return None

    async def exchange_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: RefreshToken,
        scopes: list[str],
    ) -> OAuthToken:
        """Exchange refresh token (not implemented)."""
        raise HTTPException(400, "Refresh tokens not supported")

    async def load_access_token(self, token: str) -> AccessToken | None:
        """Load and validate access token (for introspection)."""
        access_token = self.tokens.get(token)
        if not access_token:
            return None

        # Check if expired
        if access_token.expires_at and access_token.expires_at < time.time():
            del self.tokens[token]
            return None

        return access_token

    async def revoke_token(self, token: str, token_type_hint: str | None = None) -> None:  # type: ignore
        """Revoke a token."""
        if token in self.tokens:
            del self.tokens[token]

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
                user_ctx = data.get("userCtx", {})
                authenticated_username = user_ctx.get("name")
                roles = user_ctx.get("roles", [])

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
                redirect_uri=AnyHttpUrl(auth_params["redirect_uri"]),
                redirect_uri_provided_explicitly=auth_params["redirect_uri_provided_explicitly"],
                expires_at=time.time() + 300,
                scopes=auth_params.get("scopes", ["user"]),
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
