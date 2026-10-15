create table if not exists note
(
    id   bigint primary key auto_increment,
    body text not null
);
