create table test(a string, b int, d boolean);
insert into test values('a', 1, true);
insert into test values('b', 2, false);
select * from test order by a desc;

truncate table test;
insert into test select to_string(number), number, false from numbers(100000);
select min(a), max(b), count() from test;

select '1';select 2; select 1+2;

-- ignore this line

select /* ignore this block */ 'with comment';

/* ignore this block /* /*
select 'in comment block';
*/

select 1.00 + 2.00, 3.00;

select/*+ SET_VAR(timezone='Asia/Shanghai') */ timezone();

select 'bye';
drop table test;
