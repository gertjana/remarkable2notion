# Setup Guide

Complete setup instructions for reMarkable to Notion sync.

## 1. Install Prerequisites

### Rust

```bash
# Install Rust (if not already installed)
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

### ReMarkable USB Sync Tool

**macOS:**
```bash
brew install remarkablesync
```

**Linux:**
See [RemarkableSync documentation](https://github.com/lucasrla/remarkablesync)

**Alternative:**
You can also use [rmapi](https://github.com/juruen/rmapi) if you prefer.

### PDF Tools

**macOS:**
```bash
brew install poppler
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt-get install poppler-utils
```

## 2. Google Cloud Setup

### Enable APIs

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing one
3. Enable **Google Cloud Vision API**:
   - Go to "APIs & Services" → "Library"
   - Search for "Cloud Vision API"
   - Click "Enable"
4. Enable **Google Drive API**:
   - Same process, search for "Google Drive API"
   - Click "Enable"

### Create API Key (for Vision API)

1. Go to "APIs & Services" → "Credentials"
2. Click "Create Credentials" → "API Key"
3. Copy the API key
4. (Optional) Restrict the key to "Cloud Vision API" only

### Create OAuth Credentials (for Drive API)

1. Go to "APIs & Services" → "Credentials"
2. Click "Create Credentials" → "OAuth client ID"
3. If prompted, configure OAuth consent screen:
   - User Type: External (or Internal if using Google Workspace)
   - App name: "ReMarkable Sync"
   - User support email: your email
   - Developer contact: your email
   - Scopes: Add `https://www.googleapis.com/auth/drive.file`
   - Test users: Add your email
4. Application type: **Desktop app**
5. Name: "ReMarkable Sync Desktop"
6. Click "Create"
7. Download the JSON credentials file
8. Save as `scripts/credentials.json` in the project directory

### Extract OAuth Credentials

Run the helper script to extract credentials to `.env`:

```bash
./scripts/extract_oauth_credentials.sh
```

This will extract `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET` from the JSON file.

## 3. Notion Setup

### Create Integration

1. Go to https://www.notion.so/my-integrations
2. Click "New integration"
3. Name: "ReMarkable Sync"
4. Associated workspace: Select your workspace
5. Click "Submit"
6. Copy the "Internal Integration Token" (starts with `secret_`)

### Create Database

1. In Notion, create a new page
2. Add a database (full page or inline)
3. Add these properties to your database:
   - **Name** (Title) - Default property
   - **Created** (Date) - Optional
   - **Modified** (Date) - Optional
   - **PDF Link** (URL) - For Google Drive PDF link
   - **Tags** (Multi-select) - For reMarkable tags
4. Share the database with your integration:
   - Click "..." menu on the database
   - Click "Add connections"
   - Select your "ReMarkable Sync" integration

### Get Database ID

From your database URL:
- URL: `https://www.notion.so/[workspace]/[Title]-[DatabaseID]?v=...`
- Example: `https://www.notion.so/myworkspace/Notebooks-2f8effc5faf880d89807fcbbf48f85af`
- Database ID: `2f8effc5faf880d89807fcbbf48f85af` (32-character hex string)

## 4. Configuration

Create a `.env` file in the project root:

```bash
# Notion
NOTION_TOKEN=secret_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
NOTION_DATABASE_ID=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# Google Cloud Vision (required for OCR)
GOOGLE_VISION_API_KEY=AIzaSyAxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# Google Drive OAuth (required for PDF storage)
GOOGLE_OAUTH_CLIENT_ID=xxxxx.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_SECRET=GOCSPX-xxxxxxxxxxxxxxxxxxxxx

# Optional: Google Drive folder for PDFs
# Get folder ID from folder URL: https://drive.google.com/drive/folders/FOLDER_ID
GOOGLE_DRIVE_FOLDER_ID=xxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**Note:** If you used `extract_oauth_credentials.sh`, the OAuth credentials are already filled in.

## 5. Build & Test

### Build

```bash
cargo build --release
```

### Test Notion Connection

```bash
cargo run --release -- test --notion
```

Expected output:
```
✓ Successfully connected to Notion database
Database: Notebooks
Properties: Name, Created, Modified, PDF Link, Tags
```

### Test OCR (Optional)

```bash
# Create a test PDF first or use an existing one
cargo run --release -- test --ocr /path/to/sample.pdf
```

## 6. First Sync

### Prepare reMarkable

1. Connect reMarkable via USB cable
2. Ensure tablet is **awake** (tap screen if sleeping)
3. Run RemarkableSync to create backup:

```bash
remarkablesync
```

This creates a backup in `~/remarkable_backup/`.

### Run First Sync

```bash
cargo run --release -- sync
```

**OAuth Authorization (First Time Only):**
- Your browser will automatically open
- Sign in to your Google account
- Grant permissions for Drive access
- Return to terminal - sync will continue automatically
- Token is saved in `~/.config/remarkable2notion/google_token.json`
- Future syncs use the token automatically (auto-refresh when expired)

## 7. Regular Usage

### Workflow

1. Update notebooks on reMarkable
2. Connect via USB
3. Run RemarkableSync: `remarkablesync`
4. Sync to Notion: `cargo run --release -- sync`

### Tips

- Keep reMarkable awake during sync (tap screen occasionally)
- RemarkableSync creates incremental backups in `~/remarkable_backup/`
- Check Notion database after sync to see your notebooks
- Tags from reMarkable appear in the "Tags" column in Notion
- PDF links in "PDF Link" column open files in Google Drive

## Troubleshooting

### "remarkablesync not found"

Install RemarkableSync:
```bash
brew install remarkablesync  # macOS
```

### "pdftoppm not found"

Install poppler:
```bash
brew install poppler  # macOS
sudo apt-get install poppler-utils  # Linux
```

### OAuth Browser Not Opening

- Ensure you're in a desktop environment with a browser
- Check port 8085 is not blocked by firewall
- Token location: `~/.config/remarkable2notion/google_token.json`
- Delete token file to re-authorize

### "Notion API error: object_not_found"

- Database ID is incorrect - check the URL
- Integration not connected to database:
  - Open database in Notion
  - Click "..." → "Add connections"
  - Select your integration

### "Notion API error: validation_error" for Tags

- Ensure database has a "Tags" property
- Property type must be "Multi-select"
- Name must be exactly "Tags" (case-sensitive)

### "Google Vision API error: 403"

- API key is incorrect
- Cloud Vision API not enabled:
  - Go to Google Cloud Console
  - "APIs & Services" → "Library"
  - Enable "Cloud Vision API"

### "OAuth error: invalid_client"

- OAuth credentials are incorrect
- Re-run `./scripts/extract_oauth_credentials.sh`
- Ensure `scripts/credentials.json` is from a Desktop app OAuth client

### "Failed to sync notebook: IO error"

- reMarkable not connected via USB
- Tablet is sleeping - tap screen to wake
- RemarkableSync not run - run `remarkablesync` first
- Backup directory missing - check `~/remarkable_backup/`

### Images Not Appearing in Notion

- Check file sizes (must be under 5MB for Notion Free plan)
- Verify Notion API version (2025-09-03 for file uploads - handled automatically)
- Check error messages in debug mode: `RUST_LOG=debug cargo run --release -- sync`

### Tags Not Syncing

- Ensure "Tags" property exists in Notion database (Multi-select type)
- Tags must be added in reMarkable app (not via web interface)
- Check `.content` files exist in `~/remarkable_backup/Notebooks/`
- Run with debug logging to see tag extraction: `LOG_LEVEL=debug cargo run --release -- sync`

## Advanced Configuration

### Custom Backup Directory

By default, the tool looks for backups in `~/remarkable_backup/`. To use a different location, modify the path in `src/main.rs` or pass it as an argument (if you add that feature).

### Google Drive Folder

To organize PDFs in a specific Google Drive folder:

1. Create a folder in Google Drive
2. Get the folder ID from the URL: `https://drive.google.com/drive/folders/FOLDER_ID`
3. Add to `.env`: `GOOGLE_DRIVE_FOLDER_ID=FOLDER_ID`

### Log Levels

Control verbosity with the `LOG_LEVEL` environment variable in `.env` or command line:

```bash
# Concise output (default) - only essential progress
cargo run --release -- sync

# Debug logging - detailed operation info
LOG_LEVEL=debug cargo run --release -- sync

# Trace logging - very verbose, includes HTTP calls
LOG_LEVEL=trace cargo run --release -- sync
```

Available levels (from least to most verbose):
- `error` - Errors only
- `warn` - Warnings and errors
- `info` - General progress (default)
- `debug` - Detailed debugging
- `trace` - Very verbose

You can also add `LOG_LEVEL=debug` to your `.env` file to make it permanent.
Levels:
- `error` - Errors only
- `warn` - Warnings and errors
- `info` - General progress (default)
- `debug` - Detailed debugging
- `trace` - Very verbose

## Getting Help

1. Check this SETUP guide for common issues
2. Check [ai_reports/](ai_reports/) for technical details
3. Enable debug logging: `RUST_LOG=debug cargo run --release -- sync`
4. Open an issue with error messages and logs

## Next Steps

- Set up automatic sync with a cron job or launchd
- Add more properties to your Notion database
- Organize notebooks with Notion tags and filters
- Share your Notion database with collaborators
