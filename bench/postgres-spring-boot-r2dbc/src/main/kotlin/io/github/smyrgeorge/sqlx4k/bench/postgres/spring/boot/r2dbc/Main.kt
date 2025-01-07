package io.github.smyrgeorge.sqlx4k.bench.postgres.spring.boot.r2dbc

import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@SpringBootApplication
class Main

fun main(args: Array<String>) {
    thread {
        println("Starting (waiting 5s)...")
        Thread.sleep(5000)
        runBlocking {
            println("Warming up...")
            Sqlx4kService.INSTANCE.warmup()
            println("Running...")
            Sqlx4kService.INSTANCE.bench()
            exitProcess(0)
        }
    }

    runApplication<Main>(*args)
}