# JCF Mailer Core

**JCF Mailer Core** is an enterprise-grade, campaign-centric email marketing engine built exclusively for the Jarurat Care Foundation (JCF). It replaces generic third-party tools by providing a secure, scalable, and isolated environment for managing automated email campaigns, tracking engagement, and ensuring high deliverability using Amazon SES.

---

# Features

- **Multi-Campaign Engine**
  - Complete campaign isolation.
  - Mailing lists, templates, and analytics remain separated.
  - Zero cross-campaign recipient overlap.

- **Dynamic Link Tracking**
  - Automatically wraps outbound links.
  - Tracks user engagement using HTTP 302 redirects.
  - Updates click activity in real time.

- **Global Suppression List**
  - Maintains a centralized suppression database.
  - Automatically blocks:
    - Bounced emails
    - Spam complaints
    - Unsubscribed recipients
  - Applies across all campaigns.

- **AWS SES & SNS Integration**
  - Amazon SES for email delivery.
  - Amazon SNS webhook support for bounce and complaint processing.

- **Analytics & Export**
  - Export users who clicked tracked links.
  - Export users who did not take action.
  - CSV download support.

- **Admin Authentication**
  - Protected administrative dashboard.
  - Restricted access for campaign management.

---

# Tech Stack

**Backend**
- Java 17+
- Spring Boot 3

**Database**
- PostgreSQL 15+

**Cloud**
- Amazon EC2
- Amazon SES
- Amazon SNS

**Frontend**
- Thymeleaf
- Bootstrap 5
- Custom CSS

**Security**
- Nginx Reverse Proxy
- Let's Encrypt SSL

---

# Prerequisites

Before running the application, ensure the following are configured:

## PostgreSQL

- Running PostgreSQL instance
- Database named:

```text
jarurat_mailer
```

## AWS

- Amazon SES configured and production-ready (out of Sandbox)
- IAM User or IAM Role with SES sending permissions
- Amazon SNS webhook configured to:

```text
https://mailer.horizonevent.info/api/mailer/sns-webhook
```

## Domain

Create an A Record pointing your domain to the EC2 instance.

---

# Environment Configuration

Configure `src/main/resources/application.properties`.

```properties
# Server
server.port=8080
app.domain=https://mailer.horizonevent.info

# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/jarurat_mailer
spring.datasource.username=postgres
spring.datasource.password=YOUR_DB_PASSWORD

spring.jpa.hibernate.ddl-auto=update

# AWS
aws.region=ap-south-1
aws.ses.fromEmail=admin@horizonevent.info
```

**Note**

Do not hardcode AWS Access Keys in production. Use EC2 IAM Roles or environment variables instead.

---

# Build the Application

```bash
./mvnw clean package -DskipTests
```

---

# EC2 Deployment

## 1. Configure Nginx

Create:

```text
/etc/nginx/sites-available/mailer
```

Add:

```nginx
server {
    server_name mailer.horizonevent.info;

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

Enable the configuration:

```bash
sudo ln -s /etc/nginx/sites-available/mailer /etc/nginx/sites-enabled/
sudo systemctl restart nginx
```

---

## 2. Enable SSL

```bash
sudo certbot --nginx -d mailer.horizonevent.info
```

---

## 3. Start the Application

```bash
sudo java -jar target/mailer-0.0.1-SNAPSHOT.jar
```

---

# Usage

## Login

Open:

```text
https://mailer.horizonevent.info
```

Click **Send Bulk Emails** and log in using the administrator credentials.

---

## Create a Campaign

1. Enter a unique Campaign ID.
   Example:

```text
Summit_Invite_Aug
```

2. Enter the email subject.

3. Paste your HTML email template.

4. Supported template variables:

| Variable | Description |
|----------|-------------|
| `{{NAME}}` | Inserts recipient name |
| `{{UNSUBSCRIBE_LINK}}` | Inserts unique unsubscribe link |
| `{{TRACK:https://example.com}}` | Tracks clicks and redirects |

Example:

```text
{{TRACK:https://mailer.horizonevent.info/api/mailer/success}}
```

5. Upload a CSV file.

Required CSV format:

```csv
Name,Email
John Doe,john@example.com
Jane Doe,jane@example.com
```

6. Click **Save & Upload CSV**.

---

## Launch Campaign

- Verify audience count.
- Click **Trigger Campaign**.
- Monitor logs on the EC2 server for delivery status.

---

## Analytics

Available exports:

- Export Action Takers
- Export No Action

Recipients with bounces, complaints, or unsubscribes are automatically excluded.

---

# Security Notes

- Nginx handles ports **80** and **443**.
- Spring Boot runs internally on **8080**.
- Deleting a campaign removes only campaign-specific data.
- The Global Suppression List is always preserved.
- Dynamic tracking URLs use URL encoding to prevent malformed redirects.

---

# Project Architecture

```
Internet
    │
    ▼
Nginx (80 / 443)
    │
    ▼
Spring Boot (8080)
    │
    ├── PostgreSQL
    │
    ├── Amazon SES
    │
    └── Amazon SNS
```

---

# License

This project is developed exclusively for **Jarurat Care Foundation (JCF)**.

All rights reserved.