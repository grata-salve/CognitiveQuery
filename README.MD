# CognitiveQuery Telegram Bot

A Spring Boot application providing a Telegram bot for exploring, querying, and scheduling SQL analyses on GitHub-hosted repositories. The bot integrates with GitHub for schema extraction, uses an LLM (Gemini) for SQL generation, and supports executing queries against user-provided databases.

---

## Features

* **GitHub Integration**: Connect your GitHub account and analyze public repositories to extract database schema.
* **AI‑Powered SQL Generation**: Use a large language model (Gemini) to convert natural-language questions into SQL queries.
* **Interactive Querying**: Send `/query <your question>` to generate and execute SQL, with support for CSV or TXT exports.
* **Database Credentials Management**: Securely store and reuse database credentials for query execution.
* **Scheduled Queries**: Create, list, pause, resume, and delete recurring queries with CRON expressions.
* **Telegram‑First UX**: Inline buttons for executing generated queries and managing schedules directly in chat.

---

## Prerequisites

* Java 17+
* Maven 3.8+
* PostgreSQL (or your preferred SQL database)
* A Telegram bot token (via @BotFather)
* GitHub OAuth App credentials (client ID & secret)
* Backend API endpoint for GitHub OAuth and LLM service

---

## Installation

1. **Clone the repository**

   ```bash
   git clone https://github.com/your-org/CognitiveQuery.git
   cd CognitiveQuery
   ```

2. **Configure application properties**

   Edit `src/main/resources/application.yml` or `application.properties` with your settings:

   ```yaml
   telegram:
     bot:
       token: <YOUR_TELEGRAM_BOT_TOKEN>
       username: <YOUR_BOT_USERNAME>

   backend:
     api:
       base-url: https://api.yourbackend.com

   spring:
     datasources:
       default:
         url: jdbc:postgresql://localhost:5432/cognitive_query_db
         username: dbuser
         password: dbpassword
   ```

3. **Build the application**

   ```bash
   mvn clean package
   ```

4. **Run the bot**

   ```bash
   java -jar target/CognitiveQuery-0.0.1-SNAPSHOT.jar
   ```

5. **Set webhook or start long polling**

   This bot uses long polling by default. Ensure your server can make outbound HTTPS calls to Telegram.

---

## Usage

Interact with your bot in Telegram:

1. **/start** — Get a quick overview of available commands.
2. **/connect\_github** — Link your GitHub account via OAuth.
3. **/analyze\_repo** — Provide a public GitHub repo URL to extract schema.
4. **/list\_schemas** — View repos you’ve analyzed.
5. **/use\_schema \<repo\_url>** — Set context for future `/query` commands.
6. **/set\_db\_credentials** — Enter or update credentials for a repo’s database.
7. **/query \*\*\*\* \[--csv|--txt]** — Ask a natural-language question; receive SQL, then tap “🚀 Execute Query.”
8. **/schedule\_query** — Create a named, recurring query (CRON‑based).
9. **/list\_scheduled\_queries** — Manage your scheduled jobs (pause, resume, delete).
10. **/help** — Show the commands menu again.

---

## Architecture Overview

* **CognitiveQueryApplication**: Main Spring Boot entry point; schedules and polling enabled.
* **Telegram Bot Component**: `CognitiveQueryTelegramBot` extends `TelegramLongPollingBot`, handling updates/events.
* **Command & Input Handlers**: Modular classes for command parsing (`BotCommandHandler`), callback processing (`BotCallbackHandler`), and multi‑step user flows (`BotInputHandler`).
* **State Management**: `BotStateService` tracks each user’s FSM state and temporary inputs.
* **Persistence**: JPA repositories (`UserRepository`, `AnalysisHistoryRepository`, `ScheduledQueryRepository`) store users, analysis history, and schedules.
* **Services**:

    * **GitInfoService**: Fetches HEAD commit from a GitHub repo.
    * **ProjectAnalyzerService**: Clones & analyzes code, outputs a JSON schema file.
    * **GeminiService**: Interfaces with an LLM API to generate SQL.
    * **DynamicQueryExecutorService**: Connects to user DB and runs SQL queries.
    * **EncryptionService**: Encrypts database passwords before storage.

---

## Folder Structure

```text
src/
├── main/
│   ├── java/com/example/cognitivequery/
│   │   ├── bot/            # Telegram bot & handlers
│   │   ├── model/          # JPA entities
│   │   ├── repository/     # Spring Data JPA repos
│   │   ├── service/        # Core business logic
│   │   └── CognitiveQueryApplication.java
│   └── resources/
│       └── application.yml
└── test/                  # Unit & integration tests
```

---

## Contributing

Contributions are welcome! Please open issues and pull requests on GitHub:

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m "Add my feature"`)
4. Push to your branch (`git push origin feature/my-feature`)
5. Open a pull request