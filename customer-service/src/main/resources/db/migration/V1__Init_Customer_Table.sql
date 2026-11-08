CREATE TABLE customers (
                           id UUID PRIMARY KEY,
                           full_name VARCHAR(100) NOT NULL,
                           email VARCHAR(100) UNIQUE NOT NULL,
                           created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);