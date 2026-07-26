# Jarurat Care Foundation - Mailer Engine

This repository contains the cloud-native Java Spring Boot application used to manage and dispatch high-volume email campaigns for the Horizon Oncology Summit.

---

## 1. About Mailer Engine

This mailer engine is designed to send bulk, automated emails to doctors and participants while strictly adhering to CAN-SPAM compliance and AWS sending reputation guidelines.

Built on **Java 21**, the application leverages **Virtual Threads** (`newVirtualThreadPerTaskExecutor`) to achieve high-throughput concurrency, allowing thousands of emails to be processed simultaneously with a minimal memory footprint.

### Key Features

- **Passwordless Cloud Authentication:** Uses AWS IAM roles to securely interact with AWS services without hardcoded access keys.
- **Automated Reputation Management:** Listens for AWS SNS webhooks to automatically suppress bounced or complained email addresses.
- **CAN-SPAM Compliance:** Dynamically generates unique One-Click Unsubscribe links for every recipient.

---

## 2. How to Run & Test

To access and run the server, you need the private SSH key (`<ssh-key>.pem`). Request this file from the lead developer and save it to a secure location on your local machine (e.g., your `Downloads` folder).

### Step 0: Start the EC2 instance
Ask Developer to do it, he will do immediately or in case you have access to AWS credentials, Go to AWS Management Console -> EC2 -> instances -> select the instance `jarurat-mailer-server` -> Instance State -> Start Instance

### Step 1: Connect to the EC2 Server

Open your terminal (PowerShell/Command Prompt) and run:

```bash
cd path/to/your/folder/with/key
ssh -i "<ssh-key>.pem" ubuntu@<ELASTIC_IP>
```

### Step 2: Start the Engine

Once inside the Ubuntu server, ensure no ghost processes are blocking the port, then launch the application:

```bash
sudo pkill -f java
sudo java -jar mailer-0.0.1-SNAPSHOT.jar
```

> **Note:** To safely shut down the server, always use **Ctrl + C** in the terminal to trigger a graceful Spring Boot shutdown.

### Step 3: Trigger a Campaign

With the server running, open any web browser and hit the trigger endpoint:

```text
http://<ELASTIC_IP>/api/mailer/trigger-campaign
```

You should receive a success response in the browser, and the terminal will log the SES dispatch events.

---

## 2. AWS Architecture

This application integrates with multiple AWS services to create a secure, serverless-authenticated pipeline.

- **EC2 (Elastic Compute Cloud):** An Ubuntu Linux instance hosting the Java application.
- **EIP (Elastic IP):** The server is bound to a static public IP (`<ELASTIC_IP>`). This ensures the webhook URLs and DNS records never break if the server restarts.
- **IAM (Identity and Access Management):** The EC2 instance is assigned the `jarurat-ec2-ses-role`. The AWS SDK in the Java code automatically inherits these permissions, completely eliminating the need for `.properties` passwords.
- **SES (Simple Email Service):** The outbound mailing engine. The domain (`horizonevent.info`) is authenticated via DKIM and SPF records for high deliverability.
- **SNS (Simple Notification Service):** The `jarurat-email-alerts` topic catches **Bounces** and **Spam Complaints** from SES and forwards them as JSON POST requests to our EC2 webhook (`/api/mailer/sns-webhook`).

---

## 3. How to Deploy Code Changes

When you update the Java code on your local machine, follow this pipeline to push the changes to production.

### 1. Compile the Build (Local Machine)

In your project root folder, run:

```bash
mvnw.cmd clean package
```

### 2. Upload to the Cloud (Local Machine)

Use Secure Copy Protocol (SCP) to upload the `.jar` file to AWS:

```bash
scp -i "<ssh-key>.pem" "target/mailer-0.0.1-SNAPSHOT.jar" ubuntu@<ELASTIC_IP>:/home/ubuntu/
```

### 3. Restart the Server (EC2 Terminal)

Log into EC2 via SSH, kill the old process, and start the new one:

```bash
sudo pkill -f java
sudo java -jar mailer-0.0.1-SNAPSHOT.jar
```

---

## 4. Future Implementations

This architecture is currently a functional prototype running on an H2 in-memory database. To transition to a full production release, the following upgrades are needed:

1. **PostgreSQL Migration:** Replace the volatile H2 database with a persistent PostgreSQL database installed on the EC2 instance to ensure contact lists and suppression states survive server reboots.
2. **Background Process Manager (`systemd`):** Configure Linux to run the `.jar` as a permanent background service. This ensures the app restarts automatically upon a system crash or reboot, rather than relying on an active SSH session.
3. **Reverse Proxy & SSL (Nginx):** Install Nginx to route traffic through the domain (e.g., `mailer.horizonevent.info`) instead of the raw Elastic IP. Attach a Let's Encrypt SSL certificate to upgrade all endpoints from `http://` to `https://`.
4. **Security Hashing for Unsubscribes:** Upgrade the plain-text unsubscribe links (`?email=test@test.com`) to use cryptographic hashing (`?token=abc123xyz`) to prevent malicious bots from guessing emails and manipulating the database.
5. **Custom Inbound Routing (Zoho Alternative):** Implement AWS SES Inbound Receipt Rules paired with an S3 Bucket and AWS Lambda. This will catch replies sent to `admin@horizonevent.info`, parse the raw MIME data, and route it to an internal dashboard or Slack channel, eliminating the need for third-party inboxes like Zoho or Google Workspace.

---

## 5. Precautions & Warnings

- **DO NOT COMMIT THE `.pem` KEY:** Never commit `<ssh-key>.pem` to this repository or GitHub. If this key is leaked, malicious actors gain root access to the EC2 server.
- **Graceful Shutdowns:** Never simply close your SSH terminal window while the app is running. This creates an "orphan" process that permanently locks Port 80, requiring a `kill -9` command to fix. Always press **Ctrl + C**.
- **Sandbox Restrictions:** Until AWS Trust & Safety fully approves the account, SES operates in **Sandbox Mode**. Emails can only be sent to internally verified addresses (e.g., `jaruratcare@gmail.com`).
- **EC2 Billing:** AWS bills for EC2 instances based on whether the **hardware** is running, not whether the Java application is running. If you need to pause billing, go to the AWS Console and explicitly **Stop** the instance. Start it only when email sending is required.