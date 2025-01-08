use sqlx::{
    postgres::{PgPool, PgPoolOptions},
    Executor,
};
use std::time::Instant;

#[tokio::main]
async fn main() {
    let username = "postgres";
    let password = "postgres";
    let host = "localhost";
    let port = 15432;
    let database = "test";

    let url = format!(
        "postgres://{}:{}@{}:{}/{}",
        username, password, host, port, database
    );

    let pool: PgPool = PgPoolOptions::new()
        .min_connections(10)
        .max_connections(40)
        .connect(&url)
        .await
        .unwrap();

    let numberOfTests = 10;
    let workers = 4;
    let repeatPerWorker = 1_000;

    pool.execute("drop table if exists sqlx4k;").await.unwrap();
    pool.execute("create table sqlx4k(id integer, test text);")
        .await
        .unwrap();

    println!("[noTx]");
    let now = Instant::now();
    for _ in 0..repeatPerWorker {
        let _ = pool
            .fetch_all("insert into sqlx4k(id, test) values (65, 'test') returning sqlx4k.*;")
            .await
            .unwrap();

        let _ = pool
            .fetch_all("insert into sqlx4k(id, test) values (66, 'test') returning sqlx4k.*;")
            .await
            .unwrap();

        let _ = pool
            .fetch_all("select * from sqlx4k limit 100;")
            .await
            .unwrap();
    }
    let elapsed = now.elapsed();
    println!("[noTx] {:.2?}", elapsed);
}
