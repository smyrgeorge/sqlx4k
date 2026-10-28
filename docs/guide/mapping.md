# Mapping rows

## Row mappers

A `RowMapper` turns a `ResultSet.Row` into your own type:

```kotlin
object Sqlx4kRowMapper : RowMapper<Sqlx4k> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Sqlx4k {
        val id: ResultSet.Row.Column = row.get(0)
        val test: ResultSet.Row.Column = row.get(1)
        // Use built-in mapping methods to map the values to the corresponding type.
        return Sqlx4k(id = id.asInt(), test = test.asString())
    }
}

val res: List<Sqlx4k> = db.fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
```

For `@Table` entities, the code generator writes the row mapper for you: see
[Auto-generated RowMapper](../codegen/index.md#auto-generated-rowmapper).

## Supported types

### Binding values

These types can be bound to the parameters of a [statement](queries.md#prepared-statements) as they are:

| Kotlin type                                                | Notes                                                                                               |
|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `String`, `Char`                                           |                                                                                                     |
| `Boolean`                                                  |                                                                                                     |
| `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`          |                                                                                                     |
| `ByteArray`                                                | Binary columns (`bytea`, `BLOB`, …).                                                                |
| `kotlin.time.Instant`                                      | A point in time (`timestamptz`, …).                                                                 |
| `kotlinx.datetime.LocalDate`, `LocalTime`, `LocalDateTime` | Dates and times without a time zone.                                                                |
| `kotlin.uuid.Uuid`                                         |                                                                                                     |
| Enums                                                      | Bound by name, unless a [value converter](#custom-value-converters) is registered for the enum.     |
| Collections and arrays                                     | Expanded into one parameter per element: see [Binding collections](queries.md#binding-collections). |
| `null`                                                     | See [Binding nulls](queries.md#binding-nulls).                                                      |

Any other type (e.g. `BigDecimal`, or your own value classes) needs a
[value converter](#custom-value-converters); without one, the statement fails with an `SQLError` whose code is
`MissingValueConverter`.

### Reading values

`row.get("name")` (or `row.get(0)`, by position) returns the `ResultSet.Row.Column` of a row. `asString()` reads its
value as text, and `isNull()` tells whether it is `NULL`. The extension functions of
`io.github.smyrgeorge.sqlx4k.impl.extensions` read it as the other types:

| Function                                              | Returns                                                          |
|-------------------------------------------------------|------------------------------------------------------------------|
| `asChar()`                                            | `Char`                                                           |
| `asShort()`, `asInt()`, `asLong()`                    | `Short`, `Int`, `Long`                                           |
| `asUShort()`, `asUInt()`, `asULong()`                 | `UShort`, `UInt`, `ULong`                                        |
| `asFloat()`, `asDouble()`                             | `Float`, `Double`                                                |
| `asBoolean()`                                         | `Boolean`, from `true`/`false`, `t`/`f` or `1`/`0`               |
| `asUuid()`                                            | `kotlin.uuid.Uuid`                                               |
| `asEnum<T>()`                                         | The constant of the enum `T` with the column's value as its name |
| `asLocalDate()`, `asLocalTime()`, `asLocalDateTime()` | `kotlinx.datetime.LocalDate`, `LocalTime`, `LocalDateTime`       |
| `asInstant()`                                         | `kotlin.time.Instant`                                            |
| `asByteArray()`                                       | `ByteArray`                                                      |

Each one has an `…OrNull()` variant (`asIntOrNull()`, …) that returns `null` for a `NULL` column; the plain variant
fails with an `SQLError` whose code is `CannotDecode` instead.

```kotlin
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull

db.fetchAll("select id, created_at, external_id from orders;").getOrThrow().forEach { row ->
    val id: Long = row.get("id").asLong()
    val createdAt: Instant = row.get("created_at").asInstant()
    val externalId: Uuid? = row.get("external_id").asUuidOrNull()
}
```

PostgreSQL arrays are read with the functions of `io.github.smyrgeorge.sqlx4k.postgres.extensions` (each with its
`…OrNull()` variant too):

| Function                                                                                                   | Returns                                                 |
|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------|
| `asBooleanArray()`, `asShortArray()`, `asIntArray()`, `asLongArray()`, `asFloatArray()`, `asDoubleArray()` | `BooleanArray`, `ShortArray`, …                         |
| `asStringList()`, `asNullableStringList()`                                                                 | `List<String>`, `List<String?>`                         |
| `asUuidList()`, `asLocalDateTimeList()`, `asInstantList()`, `asByteArrayList()`                            | `List<Uuid>`, `List<LocalDateTime>`, `List<Instant>`, … |

## Custom value converters

For custom types that don't have builtin decoders, you can register custom `ValueEncoder` implementations. A
`ValueEncoder` provides bidirectional conversion between your custom type and the database representation.

**Creating a custom encoder:**

```kotlin
// Define your custom type
data class Money(val amount: BigDecimal, val currency: String) {
    override fun toString(): String = "$amount $currency"

    companion object {
        fun parse(value: String): Money {
            val parts = value.split(" ")
            return Money(BigDecimal(parts[0]), parts[1])
        }
    }
}

// Create a ValueEncoder for your type
object MoneyEncoder : ValueEncoder<Money> {
    override fun encode(value: Money): Any = value.toString()
    override fun decode(value: ResultSet.Row.Column): Money = Money.parse(value.asString())
}
```

**Registering and using custom encoders:**

```kotlin
// Create a registry and register your encoder
val registry = ValueEncoderRegistry()
    .register<Money>(MoneyEncoder)

// Use the registry when mapping rows
val mapper = Sqlx4kAutoRowMapper
val entity = mapper.map(row, registry)
```

For entity properties, a converter can also be declared directly on the property with
[`@Converter`](../codegen/entities.md#property-level-converters-converter), with no registry lookup.
