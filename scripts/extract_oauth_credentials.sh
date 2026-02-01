#!/bin/bash
# Helper script to extract OAuth client ID and secret from credentials.json
# and add them to .env file

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
CREDS_FILE="$SCRIPT_DIR/credentials.json"
ENV_FILE="$PROJECT_ROOT/.env"

if [ ! -f "$CREDS_FILE" ]; then
    echo "❌ Error: credentials.json not found in $SCRIPT_DIR"
    echo ""
    echo "Please download your OAuth credentials from:"
    echo "https://console.cloud.google.com/apis/credentials"
    echo ""
    echo "Then save them as: $CREDS_FILE"
    exit 1
fi

# Extract client ID and secret using Python
CLIENT_ID=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['installed']['client_id'])" 2>/dev/null || \
            python3 -c "import json; print(json.load(open('$CREDS_FILE'))['web']['client_id'])" 2>/dev/null)
CLIENT_SECRET=$(python3 -c "import json; print(json.load(open('$CREDS_FILE'))['installed']['client_secret'])" 2>/dev/null || \
                python3 -c "import json; print(json.load(open('$CREDS_FILE'))['web']['client_secret'])" 2>/dev/null)

if [ -z "$CLIENT_ID" ] || [ -z "$CLIENT_SECRET" ]; then
    echo "❌ Error: Could not extract credentials from $CREDS_FILE"
    exit 1
fi

echo "✅ Extracted OAuth credentials from credentials.json"
echo ""
echo "Add these to your .env file:"
echo "="  | tr ' ' '='
echo ""
echo "GOOGLE_OAUTH_CLIENT_ID=$CLIENT_ID"
echo "GOOGLE_OAUTH_CLIENT_SECRET=$CLIENT_SECRET"
echo ""

# Optionally update .env file
read -p "Do you want to automatically add/update these in .env? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    # Backup .env if it exists
    if [ -f "$ENV_FILE" ]; then
        cp "$ENV_FILE" "$ENV_FILE.backup"
        echo "📋 Backed up existing .env to .env.backup"
    fi

    # Remove old entries if they exist
    if [ -f "$ENV_FILE" ]; then
        sed -i.tmp '/^GOOGLE_OAUTH_CLIENT_ID=/d' "$ENV_FILE"
        sed -i.tmp '/^GOOGLE_OAUTH_CLIENT_SECRET=/d' "$ENV_FILE"
        rm "$ENV_FILE.tmp" 2>/dev/null
    fi

    # Add new entries
    echo "GOOGLE_OAUTH_CLIENT_ID=$CLIENT_ID" >> "$ENV_FILE"
    echo "GOOGLE_OAUTH_CLIENT_SECRET=$CLIENT_SECRET" >> "$ENV_FILE"

    echo "✅ Updated $ENV_FILE"
    echo ""
    echo "You can now run: cargo run --release"
    echo "The tool will guide you through the OAuth flow on first run."
fi
