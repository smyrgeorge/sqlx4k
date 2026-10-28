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
