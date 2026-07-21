@echo off

:: destroy old database
:: psql -d webapp -f "drop-schema.sql"

:: initialize new tables
psql -d webapp -f "000-create-users.sql"
psql -d webapp -f "001-create-confirmations.sql"
psql -d webapp -f "002-create-sessions.sql"
psql -d webapp -f "003-create-resets.sql"