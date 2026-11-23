//! `psidump <file.java>...` — print one line per declaration,
//! `path:line-end kind name parent`, the shape of `accio dump`.

use std::env;
use std::path::Path;

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.is_empty() {
        eprintln!("usage: psidump <file.java>...");
        std::process::exit(2);
    }
    let mut failed = false;
    for path in &args {
        match std::fs::read_to_string(path) {
            Ok(text) => {
                for d in psi_port::extract::declarations(&text) {
                    println!(
                        "{}:{}-{} {} {} {}",
                        Path::new(path).display(),
                        d.start_line,
                        d.end_line,
                        d.kind,
                        d.name,
                        d.parent
                    );
                }
            }
            Err(error) => {
                eprintln!("psidump: {}: {}", path, error);
                failed = true;
            }
        }
    }
    if failed {
        std::process::exit(1);
    }
}
