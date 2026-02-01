use std::fmt;

#[derive(Debug)]
pub enum Error {
    Remarkable(String),
    Ocr(String),
    Notion(String),
    Io(std::io::Error),
    Reqwest(reqwest::Error),
    Config(String),
    OAuth(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Remarkable(msg) => write!(f, "reMarkable error: {}", msg),
            Error::Ocr(msg) => write!(f, "OCR error: {}", msg),
            Error::Notion(msg) => write!(f, "Notion API error: {}", msg),
            Error::Io(err) => write!(f, "IO error: {}", err),
            Error::Reqwest(err) => write!(f, "HTTP error: {}", err),
            Error::Config(msg) => write!(f, "Configuration error: {}", msg),
            Error::OAuth(msg) => write!(f, "OAuth error: {}", msg),
        }
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(err: std::io::Error) -> Self {
        Error::Io(err)
    }
}

impl From<reqwest::Error> for Error {
    fn from(err: reqwest::Error) -> Self {
        Error::Reqwest(err)
    }
}

impl From<url::ParseError> for Error {
    fn from(err: url::ParseError) -> Self {
        Error::OAuth(format!("URL parse error: {}", err))
    }
}

impl From<serde_json::Error> for Error {
    fn from(err: serde_json::Error) -> Self {
        Error::OAuth(format!("JSON error: {}", err))
    }
}

pub type Result<T> = std::result::Result<T, Error>;
