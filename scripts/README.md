# Scripts Directory

This directory contains OAuth setup scripts and credentials.

## Files

### credentials.json
OAuth 2.0 client credentials downloaded from Google Cloud Console.

**How to get this:**
1. Go to https://console.cloud.google.com/apis/credentials
2. Create OAuth 2.0 Client ID (Desktop app)
3. Download JSON file
4. Save as `credentials.json` in this directory

### extract_oauth_credentials.sh
Helper script to extract client ID and secret from `credentials.json` and add them to the `.env` file.

**Usage:**
```bash
./scripts/extract_oauth_credentials.sh
```

This will:
- Parse `credentials.json`
- Extract `client_id` and `client_secret`
- Optionally update your `.env` file with these values

## No Python Required!

The tool now uses **pure Rust OAuth 2.0 implementation**. No Python scripts or dependencies needed.

OAuth tokens are managed automatically:
- Stored in `~/.config/remarkable2notion/google_token.json`
- Refreshed automatically when expired
- No manual token management required
