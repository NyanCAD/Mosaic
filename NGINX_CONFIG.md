# Nginx Configuration for MCP OAuth Discovery

## Problem

Nginx is blocking access to `/.well-known/*` paths with `403 Forbidden`. This prevents Claude Web from discovering the OAuth server metadata required for MCP authentication.

## Solution

Add the following configuration to your nginx server block to allow access to `.well-known` paths:

```nginx
# Allow access to .well-known paths for OAuth discovery (RFC 8615)
location ~ ^/\.well-known/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

## Full Example

Here's a complete nginx configuration example:

```nginx
server {
    listen 443 ssl http2;
    server_name nyancad.com;

    # SSL configuration
    ssl_certificate /path/to/cert.pem;
    ssl_certificate_key /path/to/key.pem;

    # Allow .well-known paths for OAuth discovery
    location ~ ^/\.well-known/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Proxy all other requests to the application
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support (for marimo)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

## Testing

After updating your nginx configuration and reloading nginx (`sudo nginx -s reload`), test that the endpoints are accessible:

```bash
# Test OAuth protected resource metadata
curl https://nyancad.com/.well-known/oauth-protected-resource/ai

# Expected response:
# {
#   "resource": "https://nyancad.com/ai",
#   "authorization_servers": ["https://nyancad.com"],
#   "bearer_methods_supported": ["header"],
#   "resource_documentation": "https://nyancad.com/"
# }

# Test OAuth authorization server metadata
curl https://nyancad.com/.well-known/oauth-authorization-server

# Should return OAuth server metadata (not 403)
```

## Why This Matters

1. **RFC 8615** defines `.well-known` URIs for well-known locations
2. **RFC 9728** uses `/.well-known/oauth-protected-resource` for OAuth discovery
3. **Claude Web** requires this discovery mechanism to initiate OAuth authentication
4. Without this configuration, Claude Web shows "disconnected" and never attempts OAuth

## References

- [RFC 8615: Well-Known URIs](https://www.rfc-editor.org/rfc/rfc8615.html)
- [RFC 9728: OAuth 2.0 Protected Resource Metadata](https://www.rfc-editor.org/rfc/rfc9728.html)
- [MCP Authorization Spec](https://modelcontextprotocol.io/specification/draft/basic/authorization)
