@echo off

:: destroy old database
sqlcmd -i "drop-schemas.sql"

:: initialize new tables
sqlcmd -i "000-create-users.sql"
sqlcmd -i "001-create-confirmations.sql"
sqlcmd -i "002-create-sessions.sql"
sqlcmd -i "003-create-resets.sql"