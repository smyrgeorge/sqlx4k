package io.github.smyrgeorge.sqlx4k.processor.test.entities

import io.github.smyrgeorge.sqlx4k.annotation.Column
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Table
import kotlinx.datetime.LocalDateTime

/**
 * Simple entity with auto-generated ID (default @Id behavior).
 * ID is excluded from INSERT, included in RETURNING.
 */
@Table("users")
data class User(
    @Id
    val id: Long,
    val name: String,
    val email: String
)

/**
 * Entity with application-generated ID (UUID-style).
 * ID is included in INSERT.
 */
@Table("products")
data class Product(
    @Id(insert = true)
    val id: String,
    val name: String,
    val price: Double
)

/**
 * Entity with timestamp columns managed by the database.
 * - createdAt: Set only on INSERT by DB default
 * - updatedAt: Auto-updated by DB trigger on every write
 */
@Table("articles")
data class Article(
    @Id
    val id: Long,
    val title: String,
    val content: String,
    @Column(insert = false, update = false)
    val createdAt: LocalDateTime,
    @Column(insert = false, update = false)
    val updatedAt: LocalDateTime
)

/**
 * Entity with a version column for optimistic locking.
 * Version is managed by the database.
 */
@Table("orders")
data class Order(
    @Id
    val id: Long,
    val customerId: Long,
    val totalAmount: Double,
    @Column(insert = false, update = false)
    val version: Int
)

/**
 * Entity with only some columns excluded from update.
 * createdAt is set on insert but never updated.
 */
@Table("comments")
data class Comment(
    @Id
    val id: Long,
    val postId: Long,
    val authorName: String,
    val content: String,
    @Column(update = false)
    val createdAt: LocalDateTime
)

/**
 * Entity with many properties to test column ordering.
 */
@Table("customers")
data class Customer(
    @Id
    val id: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phoneNumber: String?,
    val isActive: Boolean
)

/**
 * Simple entity without timestamps for basic tests.
 */
@Table("tags")
data class Tag(
    @Id
    val id: Int,
    val name: String
)
