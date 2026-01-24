# sqlx4k - Benchmarks

## Setup

- All tests were performed on my local machine.
- The database was installed inside a Docker container, using ARM64 images.
- Apart from the database drivers, the rest of the code was kept as similar as possible.

## Runs

We conducted three scenarios:

- `no-transaction`: A set of 3 queries was executed — two inserts and one fetch.
- `transaction-commit`: 3 queries were executed within a transaction (two inserts and one fetch), followed by a
  fetch after the transaction was completed.
- `transaction-rollback`: 3 queries were executed within a transaction (two inserts and one fetch), followed by a
  fetch after the transaction was rolled back.

In each scenario, we used **4 workers**. Each worker executed the test **1000 times** in a loop.
We performed **10 runs** per scenario and calculated the average execution time.
