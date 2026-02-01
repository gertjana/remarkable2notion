use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "remarkable2notion")]
#[command(about = "Sync reMarkable notebooks to Notion", long_about = None)]
#[command(version)]
pub struct Cli {
    #[command(subcommand)]
    pub command: Commands,
}

#[derive(Subcommand)]
pub enum Commands {
    #[command(about = "Sync all notebooks from reMarkable to Notion")]
    Sync {
        #[arg(long, help = "Notion API integration token")]
        notion_token: Option<String>,

        #[arg(long, help = "Notion database ID to sync to")]
        notion_database_id: Option<String>,

        #[arg(long, help = "Preview changes without making them")]
        dry_run: bool,

        #[arg(short, long, help = "Enable verbose logging")]
        verbose: bool,
    },

    #[command(about = "Test individual components")]
    Test {
        #[arg(long, help = "Test RemarkableSync connection")]
        remarkable: bool,

        #[arg(long, help = "Test OCR with a PDF file", value_name = "PDF_PATH")]
        ocr: Option<String>,

        #[arg(long, help = "Test Notion API connection")]
        notion: bool,

        #[arg(long, help = "Notion API token (for Notion test)")]
        notion_token: Option<String>,

        #[arg(long, help = "Notion database ID (for Notion test)")]
        notion_database_id: Option<String>,

        #[arg(short, long, help = "Enable verbose logging")]
        verbose: bool,
    },
}
