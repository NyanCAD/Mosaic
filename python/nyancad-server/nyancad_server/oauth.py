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

        # Redirect to login page with state parameter (custom endpoint with /oauth prefix)
        return f"{SERVER_URL}/oauth/login?state={state}"

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

    async def validate_credentials(self, username: str, password: str) -> tuple[bool, list[str]]:
        """Validate credentials against CouchDB and return user roles."""
        async with httpx.AsyncClient() as client:
            try:
                response = await client.post(
                    f"{COUCHDB_URL}/_session",
                    data={"name": username, "password": password},
                    headers={"Content-Type": "application/x-www-form-urlencoded"}
                )

                if response.status_code == 200:
                    data = response.json()
                    user_ctx = data.get("userCtx", {})
                    roles = user_ctx.get("roles", [])
                    return True, roles

                return False, []
            except Exception as e:
                logger.error(f"CouchDB authentication error: {e}")
                return False, []

    async def get_login_page(self, state: str) -> HTMLResponse:
        """Generate login page HTML."""
        if not state or state not in self.state_mapping:
            raise HTTPException(400, "Invalid or missing state parameter")

        html_content = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <title>NyanCAD Authentication</title>
            <style>
                body {{
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
                    max-width: 400px;
                    margin: 50px auto;
                    padding: 20px;
                    background-color: #f5f5f5;
                }}
                .container {{
                    background: white;
                    padding: 30px;
                    border-radius: 8px;
                    box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                }}
                h2 {{
                    margin-top: 0;
                    color: #333;
                }}
                .form-group {{
                    margin-bottom: 20px;
                }}
                label {{
                    display: block;
                    margin-bottom: 5px;
                    color: #666;
                    font-size: 14px;
                }}
                input {{
                    width: 100%;
                    padding: 10px;
                    border: 1px solid #ddd;
                    border-radius: 4px;
                    font-size: 14px;
                    box-sizing: border-box;
                }}
                button {{
                    width: 100%;
                    background-color: #007bff;
                    color: white;
                    padding: 12px;
                    border: none;
                    border-radius: 4px;
                    cursor: pointer;
                    font-size: 16px;
                    font-weight: 500;
                }}
                button:hover {{
                    background-color: #0056b3;
                }}
                .error {{
                    color: #dc3545;
                    margin-top: 10px;
                    font-size: 14px;
                }}
            </style>
        </head>
        <body>
            <div class="container">
                <h2>Sign in to NyanCAD</h2>
                <form action="{SERVER_URL}/oauth/login/callback" method="post">
                    <input type="hidden" name="state" value="{state}">
                    <div class="form-group">
                        <label>Username</label>
                        <input type="text" name="username" required autofocus>
                    </div>
                    <div class="form-group">
                        <label>Password</label>
                        <input type="password" name="password" required>
                    </div>
                    <button type="submit">Sign In</button>
                </form>
            </div>
        </body>
        </html>
        """

        return HTMLResponse(content=html_content)

    async def handle_login_callback(self, request: Request) -> Response:
        """Handle login form submission and create authorization code."""
        form = await request.form()
        username = form.get("username")
        password = form.get("password")
        state = form.get("state")

        if not username or not password or not state:
            raise HTTPException(400, "Missing required parameters")

        # Ensure strings (not UploadFile objects)
        username = str(username)
        password = str(password)
        state = str(state)

        # Get stored authorization parameters
        auth_params = self.state_mapping.get(state)
        if not auth_params:
            raise HTTPException(400, "Invalid state parameter")

        # Validate credentials with CouchDB
        valid, roles = await self.validate_credentials(username, password)
        if not valid:
            # Return login page with error
            html_content = await self.get_login_page(state)
            error_html = html_content.body.decode().replace(
                '</form>',
                '<div class="error">Invalid username or password</div></form>'
            )
            return HTMLResponse(content=error_html, status_code=401)

        # Generate authorization code
        code = f"nyancad_{secrets.token_hex(16)}"

        # Create authorization code with CouchDB user data
        auth_code = AuthorizationCode(
            code=code,
            client_id=auth_params["client_id"],
            redirect_uri=AnyHttpUrl(auth_params["redirect_uri"]),
            redirect_uri_provided_explicitly=auth_params["redirect_uri_provided_explicitly"],
            expires_at=time.time() + 300,  # 5 minutes
            scopes=auth_params.get("scopes", ["user"]),
            code_challenge=auth_params.get("code_challenge"),
            resource=auth_params.get("resource"),
        )

        # Store authorization code
        self.auth_codes[code] = auth_code

        # Store user data separately (Pydantic doesn't allow adding fields)
        self.user_data[code] = {
            "username": username,
            "roles": roles,
        }

        # Clean up state mapping
        del self.state_mapping[state]

        # Redirect back to client with authorization code
        redirect_uri = construct_redirect_uri(
            auth_params["redirect_uri"],
            code=code,
            state=state,
        )

        return RedirectResponse(url=redirect_uri, status_code=302)


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

    # Add custom login page route (GET) - prefixed with /oauth for clarity
    async def login_page_handler(request: Request) -> Response:
        """Show login form."""
        state = request.query_params.get("state")
        if not state:
            raise HTTPException(400, "Missing state parameter")
        return await oauth_provider.get_login_page(state)

    routes.append(Route("/oauth/login", endpoint=login_page_handler, methods=["GET"]))

    # Add custom login callback route (POST) - prefixed with /oauth for clarity
    async def login_callback_handler(request: Request) -> Response:
        """Handle login form submission."""
        return await oauth_provider.handle_login_callback(request)

    routes.append(Route("/oauth/login/callback", endpoint=login_callback_handler, methods=["POST"]))

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
