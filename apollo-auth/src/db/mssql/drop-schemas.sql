USE [webapp]
GO

declare @n char(1)
set @n = char(10)

declare @stmt nvarchar(max)

-- procedures
select @stmt = isnull( @stmt + @n, '' ) +
    'drop procedure [' + schema_name(schema_id) + '].[' + name + ']'
from sys.procedures

-- check constraints
select @stmt = isnull( @stmt + @n, '' ) +
'alter table [' + schema_name(schema_id) + '].[' + object_name( parent_object_id ) + ']    drop constraint [' + name + ']'
from sys.check_constraints

-- views
select @stmt = isnull( @stmt + @n, '' ) +
    'drop view [' + schema_name(schema_id) + '].[' + name + ']'
from sys.views

-- foreign keys
select @stmt = isnull( @stmt + @n, '' ) +
    'alter table [' + schema_name(schema_id) + '].[' + object_name( parent_object_id ) + '] drop constraint [' + name + ']'
from sys.foreign_keys

-- tables
select @stmt = isnull( @stmt + @n, '' ) +
    'drop table [' + schema_name(schema_id) + '].[' + name + ']'
from sys.tables

-- user defined types
select @stmt = isnull( @stmt + @n, '' ) +
    'drop type [' + schema_name(schema_id) + '].[' + name + ']'
from sys.types
where is_user_defined = 1

-- functions
select @stmt = isnull( @stmt + @n, '' ) +
    'drop function [' + schema_name(schema_id) + '].[' + name + ']'
from sys.objects
where type in ( 'FN', 'IF', 'TF' )

-- sequences
select @stmt = isnull( @stmt + @n, '' ) +
    'drop sequence [' + schema_name(schema_id) + '].[' + name + ']'
from sys.sequences

-- partition schemes
select @stmt = isnull( @stmt + @n, '' ) +
    'drop partition scheme [' + name + ']'
from sys.partition_schemes

-- partition functions
select @stmt = isnull( @stmt + @n, '' ) +
    'drop partition function [' + name + ']'
from sys.partition_functions

exec sp_executesql @stmt
go