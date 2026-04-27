# WhatsApp Webhook Spring Boot Application

A simple Spring Boot application that exposes a webhook endpoint for receiving WhatsApp messages.

## Features
-   Runs on port **3002**.
-   **GET `/webhook`**: Returns `Webhook working`.
-   **POST `/webhook`**: 
    -   Accepts JSON body.
    -   Prints request body to console.
    -   Extracts phone number and message text.
    -   Returns `{"status": "received"}`.

## Prerequisites
-   Java 17 or higher.
-   Maven (or your favorite IDE like IntelliJ/Eclipse).

## How to Run

### Using Maven
If you have Maven installed, navigate to this folder and run:
```bash
mvn spring-boot:run
```

### Using IDE
1.  Open this folder in your IDE.
2.  Wait for dependencies to download (set up as a Maven project).
3.  Run `WebhookApplication.java`.

## Exposing to Internet (ngrok)
To test this with AiSensy or any WhatsApp provider, you need to expose your local port 3002:

```bash
ngrok http 3002
```

Once ngrok provides a URL like `https://xxxx.ngrok.io`, your webhook URL will be:
`https://xxxx.ngrok.io/webhook`

## Troubleshooting
-   Ensure port **3002** is free.
-   If you don't have Maven installed, you can use the built-in Runner in IntelliJ or Eclipse.
