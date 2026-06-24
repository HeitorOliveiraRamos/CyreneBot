# CyreneBot Database Setup Guide

## Overview
CyreneBot uses **PostgreSQL** as its database with **Flyway** for database migrations. The application will automatically run all migrations on startup.

## What You Need to Create

### 1. PostgreSQL Database: `cyrene`
Name: **cyrene**
- Used by the application connection string: `jdbc:postgresql://localhost:5432/cyrene`

### 2. PostgreSQL Role/User: `cyrene`
Username: **cyrene**
Password: **cyrene** (default, can be changed via environment variables)
Privileges: 
- `CREATEDB` - to allow database creation/usage
- Full permissions on the `cyrene` database

## Step-by-Step Setup Instructions

### Prerequisites
- PostgreSQL installed and running on `localhost:5432`
- `psql` command-line tool available

### Option A: Quick Setup (Using psql)

```bash
# 1. Connect to PostgreSQL as superuser (usually 'postgres')
psql -U postgres -h localhost

# 2. Create the cyrene role with password
CREATE ROLE cyrene WITH LOGIN PASSWORD 'cyrene' CREATEDB;

# 3. Create the cyrene database owned by the cyrene role
CREATE DATABASE cyrene OWNER cyrene;

# 4. Grant all privileges on the database
GRANT ALL PRIVILEGES ON DATABASE cyrene TO cyrene;

# 5. Exit psql
\q
```

### Option B: Setup Script (All in One)

```bash
# Run this command directly (copy the entire SQL statement):
psql -U postgres -h localhost -c "
  CREATE ROLE cyrene WITH LOGIN PASSWORD 'cyrene' CREATEDB;
  CREATE DATABASE cyrene OWNER cyrene;
  GRANT ALL PRIVILEGES ON DATABASE cyrene TO cyrene;
"
```

### Option C: Using a SQL File

Create a file `setup_cyrene.sql`:
```sql
-- Create the cyrene role with password
CREATE ROLE cyrene WITH LOGIN PASSWORD 'cyrene' CREATEDB;

-- Create the cyrene database owned by cyrene
CREATE DATABASE cyrene OWNER cyrene;

-- Grant all privileges on the cyrene database to the cyrene role
GRANT ALL PRIVILEGES ON DATABASE cyrene TO cyrene;
```

Then run:
```bash
psql -U postgres -h localhost -f setup_cyrene.sql
```

## Verify Setup

After creation, verify everything is correctly set up:

```bash
# Test connection as the cyrene user
psql -U cyrene -h localhost -d cyrene

# You should now be connected to the cyrene database
# Exit with:
\q
```

## Environment Variables (Optional)

If you want to use different credentials, modify your `.env` file:

```dotenv
DB_USER=cyrene          # Change this to your username
DB_PASSWORD=cyrene      # Change this to your password
```

These are read from `.env` and override the default values in `application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/cyrene
    username: ${DB_USER:cyrene}      # Default: cyrene
    password: ${DB_PASSWORD:cyrene}  # Default: cyrene
```

## Database Schema

The application will automatically create the following tables on first startup via Flyway migrations:

### Core Tables
- **conversa** - Conversation sessions
- **troca_conversa** - Individual exchanges within conversations
- **mensagem_mencao** - @-mention interactions
- **usuario** - User profiles and memory

### Moderation Tables
- **moderation_warning** - Moderation warning records

## Automatic Migration

Once database and role are created:
1. Start the application: `mvn spring-boot:run` or run the JAR
2. Flyway will automatically:
   - Detect the `cyrene` database
   - Apply all 8 migration files in order
   - Create all tables and indexes

The migration status is logged during startup. All migrations are idempotent (safe to run multiple times).

## Troubleshooting

### Connection Refused
- Verify PostgreSQL is running: `brew services list` (macOS) or check service status
- Verify host and port: default is `localhost:5432`
- Check firewall rules if using remote PostgreSQL

### Role Already Exists
If you get "role 'cyrene' already exists", either:
- Use it as-is (it's already set up)
- Drop and recreate: `DROP ROLE IF EXISTS cyrene;` then run creation SQL

### Permission Denied
Ensure the role has `CREATEDB` privilege:
```sql
ALTER ROLE cyrene CREATEDB;
ALTER ROLE cyrene WITH LOGIN;
```

### Database Already Exists
If the database exists but you want fresh data:
```bash
# WARNING: This drops all data!
psql -U postgres -h localhost -c "DROP DATABASE IF EXISTS cyrene;"
# Then create again following the steps above
```

## Security Notes

⚠️ **For Development Only**: The default credentials (`cyrene`/`cyrene`) are suitable for local development only.

For production:
1. Use strong passwords
2. Store credentials securely (AWS Secrets Manager, HashiCorp Vault, etc.)
3. Restrict database network access
4. Use SSL connections to PostgreSQL
5. Consider separate read/write roles

## Next Steps

After database setup, the application is ready to:
1. Connect to the `cyrene` database
2. Run all Flyway migrations automatically
3. Store conversation history
4. Track user profiles and memory
5. Log moderation warnings

For full bot functionality, also ensure:
- Discord Bot Token is set in `.env` (BOT_TOKEN)
- Ollama is running and accessible (OLLAMA_BASE_URL)
- Required Ollama models are available (MODEL_NAME, BRAIN_MODEL_NAME, VOICE_MODEL_NAME)

