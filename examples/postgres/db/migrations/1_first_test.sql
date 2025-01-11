drop table if exists sqlx4k;
create table if not exists sqlx4k
(
    id   integer,
    test text
);
insert into sqlx4k (id, test)
values (65, 'test')