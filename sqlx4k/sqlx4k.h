#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

#define OK -1
#define ERROR_DATABASE 0
#define ERROR_POOL_TIMED_OUT 1
#define ERROR_POOL_CLOSED 2
#define ERROR_WORKER_CRASHED 3

typedef struct Ptr {
  void *ptr;
} Ptr;

typedef struct Sqlx4kColumn {
  int ordinal;
  char *name;
  char *kind;
  char *value;
} Sqlx4kColumn;

typedef struct Sqlx4kRow {
  int size;
  struct Sqlx4kColumn *columns;
} Sqlx4kRow;

typedef struct Sqlx4kResult {
  int error;
  char *error_message;
  unsigned long long rows_affected;
  void *tx;
  int size;
  struct Sqlx4kRow *rows;
} Sqlx4kResult;

void sqlx4k_free_result(struct Sqlx4kResult *ptr);

Sqlx4kResult *sqlx4k_of(const char *host,
                        int port,
                        const char *username,
                        const char *password,
                        const char *database,
                        int max_connections);

int sqlx4k_pool_size(void);

int sqlx4k_pool_idle_size(void);

void sqlx4k_close(void *callback, void (*fun)(Ptr, Sqlx4kResult*));

void sqlx4k_query(const char *sql, void *callback, void (*fun)(Ptr, Sqlx4kResult*));

void sqlx4k_fetch_all(const char *sql, void *callback, void (*fun)(Ptr, Sqlx4kResult*));

void sqlx4k_tx_begin(void *callback, void (*fun)(Ptr, Sqlx4kResult*));

void sqlx4k_tx_commit(void *tx, void *callback, void (*fun)(Ptr, Sqlx4kResult*));

void sqlx4k_tx_rollback(void *tx, void *callback, void (*fun)(Ptr, Sqlx4kResult*));

void sqlx4k_tx_query(void *tx, const char *sql, void *callback, void (*fun)(Ptr, Sqlx4kResult*));

void sqlx4k_tx_fetch_all(void *tx,
                         const char *sql,
                         void *callback,
                         void (*fun)(Ptr, Sqlx4kResult*));
