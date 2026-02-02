mod cli;
mod config;
mod error;
mod google_drive;
mod google_vision;
mod notion;
mod oauth;
mod remarkable;
mod sync;
mod test;

use clap::Parser;
use cli::{Cli, Commands};
use config::Config;
use std::path::{Path, PathBuf};
use sync::SyncEngine;
use tracing::Level;
use tracing_subscriber::FmtSubscriber;

#[tokio::main]
async fn main() {
    // Load .env file if it exists
    dotenvy::dotenv().ok();

    let cli = Cli::parse();

    match cli.command {
        Commands::Sync {
            notion_token,
            notion_database_id,
            dry_run,
            verbose,
        } => {
            // Log level from env var LOG_LEVEL or --verbose flag
            let level = std::env::var("LOG_LEVEL")
                .ok()
                .and_then(|l| match l.to_lowercase().as_str() {
                    "trace" => Some(Level::TRACE),
                    "debug" => Some(Level::DEBUG),
                    "info" => Some(Level::INFO),
                    "warn" => Some(Level::WARN),
                    "error" => Some(Level::ERROR),
                    _ => None,
                })
                .unwrap_or(if verbose { Level::DEBUG } else { Level::INFO });

            let subscriber = FmtSubscriber::builder().with_max_level(level).finish();
            tracing::subscriber::set_global_default(subscriber)
                .expect("Failed to set tracing subscriber");

            // Print ASCII art header
            const VERSION: &str = env!("CARGO_PKG_VERSION");
            eprintln!();
            eprintln!("     _____          ___    _____");
            eprintln!(" ___|     |        |_  |  |   | |");
            eprintln!("|  _| | | |        |  _|  | | | |");
            eprintln!("|_| |_|_|_|arkable |___|  |_|___|otion v{}", VERSION);
            eprintln!();

            let notion_token = notion_token
                .or_else(|| std::env::var("NOTION_TOKEN").ok())
                .unwrap_or_else(|| {
                    eprintln!("Error: NOTION_TOKEN not provided via --notion-token or NOTION_TOKEN env var");
                    std::process::exit(1);
                });

            let notion_database_id = notion_database_id
                .or_else(|| std::env::var("NOTION_DATABASE_ID").ok())
                .unwrap_or_else(|| {
                    eprintln!("Error: NOTION_DATABASE_ID not provided via --notion-database-id or NOTION_DATABASE_ID env var");
                    std::process::exit(1);
                });

            let remarkable_backup_dir = std::env::var("REMARKABLE_BACKUP_DIR")
                .ok()
                .map(PathBuf::from);

            let remarkable_password = std::env::var("REMARKABLE_PASSWORD").ok();

            let config = match Config::new(
                notion_token,
                notion_database_id,
                remarkable_backup_dir,
                remarkable_password,
                dry_run,
                verbose,
            ) {
                Ok(cfg) => cfg,
                Err(e) => {
                    eprintln!("Configuration error: {}", e);
                    std::process::exit(1);
                }
            };

            let engine = match SyncEngine::new(config).await {
                Ok(eng) => eng,
                Err(e) => {
                    eprintln!("Failed to initialize sync engine: {}", e);
                    std::process::exit(1);
                }
            };

            if let Err(e) = engine.verify_prerequisites().await {
                eprintln!("Prerequisites check failed: {}", e);
                eprintln!("\nPlease ensure:");
                eprintln!("  1. RemarkableSync is installed (brew install remarkablesync)");
                eprintln!("  2. Tesseract is installed (brew install tesseract)");
                eprintln!("  3. Notion token and database ID are correct");
                eprintln!("  4. ReMarkable tablet is connected via USB");
                std::process::exit(1);
            }

            if let Err(e) = engine.sync().await {
                eprintln!("Sync failed: {}", e);
                std::process::exit(1);
            }
        }

        Commands::Test {
            remarkable,
            ocr,
            notion,
            notion_token,
            notion_database_id,
            verbose,
        } => {
            let level = if verbose { Level::DEBUG } else { Level::INFO };
            let subscriber = FmtSubscriber::builder().with_max_level(level).finish();
            tracing::subscriber::set_global_default(subscriber)
                .expect("Failed to set tracing subscriber");

            if remarkable {
                let backup_dir = std::env::var("REMARKABLE_BACKUP_DIR")
                    .ok()
                    .map(PathBuf::from);
                let password = std::env::var("REMARKABLE_PASSWORD").ok();

                if let Err(e) = test::test_remarkable(backup_dir, password).await {
                    eprintln!("RemarkableSync test failed: {}", e);
                    std::process::exit(1);
                }
            }

            if let Some(ref pdf_path) = ocr {
                if let Err(e) = test::test_ocr(Path::new(pdf_path)).await {
                    eprintln!("OCR test failed: {}", e);
                    std::process::exit(1);
                }
            }

            if notion {
                let token = notion_token
                    .or_else(|| std::env::var("NOTION_TOKEN").ok())
                    .unwrap_or_else(|| {
                        eprintln!("Error: NOTION_TOKEN required for Notion test");
                        std::process::exit(1);
                    });

                let db_id = notion_database_id
                    .or_else(|| std::env::var("NOTION_DATABASE_ID").ok())
                    .unwrap_or_else(|| {
                        eprintln!("Error: NOTION_DATABASE_ID required for Notion test");
                        std::process::exit(1);
                    });

                if let Err(e) = test::test_notion(&token, &db_id).await {
                    eprintln!("Notion test failed: {}", e);
                    std::process::exit(1);
                }
            }

            if !remarkable && ocr.is_none() && !notion {
                eprintln!("Please specify at least one test: --remarkable, --ocr, or --notion");
                eprintln!("Run with --help for more information");
                std::process::exit(1);
            }
        }
    }
}
