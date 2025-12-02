"""JWT token verification for MCP requests."""

import jwt
from mcp.server.auth.provider import AccessToken
from .config import JWT_SECRET, COUCHDB_URL


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
            print("ERROR: JWT_SECRET environment variable not set")
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
            print("Token verification failed: Token expired")
            return None
        except jwt.InvalidAudienceError:
            print(f"Token verification failed: Invalid audience (expected {COUCHDB_URL})")
            return None
        except jwt.InvalidTokenError as e:
            print(f"Token verification failed: {e}")
            return None
        except Exception as e:
            print(f"Token verification error: {e}")
            return None
