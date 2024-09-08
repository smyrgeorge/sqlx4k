extern crate cbindgen;

use std::env;

fn main() {
    let crate_dir = env::var("CARGO_MANIFEST_DIR").unwrap();
    let mut config: cbindgen::Config = cbindgen::Config {
        includes: vec!["../../../sqlx4k/rust_lib/target/sqlx4k.h".to_string()],
        ..Default::default()
    };
    config.language = cbindgen::Language::C;
    cbindgen::generate_with_config(&crate_dir, config)
        .unwrap()
        .write_to_file("target/sqlx4k_sqlite.h");
}
