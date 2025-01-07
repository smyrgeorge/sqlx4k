package io.github.smyrgeorge.sqlx4k.bench.postgres.spring.boot.r2dbc

import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.concurrent.thread

@SpringBootApplication
class Main

fun main(args: Array<String>) {
    thread {
        Thread.sleep(5000)
        runBlocking {
            println("Warmup...")
            Sqlx4kService.INSTANCE.warmup()
            println("Running...")
            Sqlx4kService.INSTANCE.bench()
        }
    }

    runApplication<Main>(*args)
}