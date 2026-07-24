package com.bank.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DigitalMoneyTransferServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalMoneyTransferServiceApplication.class, args);
    }

}
