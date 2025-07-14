package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.Driver

interface IMySQL : Driver, Driver.Pool, Driver.Transactional, Driver.Migrate