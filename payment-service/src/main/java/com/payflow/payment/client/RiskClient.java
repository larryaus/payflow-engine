package com.payflow.payment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "risk-service", url = "${services.risk.url}")
public interface RiskClient {

    @PostMapping("/api/v1/risk/check")
    boolean checkRisk(
            @RequestParam("from_account") String fromAccount,
            @RequestParam("to_account") String toAccount,
            @RequestParam("amount") Long amount);
}
