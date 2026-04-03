#!/bin/bash
echo "[LocalStack] Creating DynamoDB table: trade-signals"
awslocal dynamodb create-table \
    --table-name trade-signals \
    --attribute-definitions AttributeName=id,AttributeType=S \
    --key-schema AttributeName=id,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region us-east-1
echo "[LocalStack] trade-signals table created"
