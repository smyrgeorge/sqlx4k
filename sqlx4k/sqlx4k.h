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

typedef struct Sqlx4kSchemaColumn {
  int ordinal;
  char *name;
  char *kind;
} Sqlx4kSchemaColumn;

typedef struct Sqlx4kSchema {
  int size;
  struct Sqlx4kSchemaColumn *columns;
} Sqlx4kSchema;

typedef struct Sqlx4kColumn {
  int ordinal;
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
  void *cn;
  void *tx;
  void *rt;
  struct Sqlx4kSchema *schema;
  int size;
  struct Sqlx4kRow *rows;
} Sqlx4kResult;

void auto_generated_for_struct_Ptr(struct Ptr);
void auto_generated_for_struct_Sqlx4kResult(struct Sqlx4kResult);
void sqlx4k_free_result(struct Sqlx4kResult *ptr);
