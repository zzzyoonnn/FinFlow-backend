package com.FinFlow.controller;

import com.FinFlow.config.auth.LoginUser;
import com.FinFlow.dto.ResponseDTO;
import com.FinFlow.dto.transaction.TransactionRespDTO.TransactionListRespDTO;
import com.FinFlow.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TransactionController {

  private final TransactionService transactionService;

  @GetMapping("/s/account/{number}/transaction")
  public ResponseEntity<?> getAccountTransactions(@PathVariable("number") String number,
                                          @RequestParam(value = "transaction_type", defaultValue = "ALL") String transactionType,
                                          @RequestParam(value = "page", defaultValue = "0") Integer page,
                                          @AuthenticationPrincipal LoginUser loginUser) {

    TransactionListRespDTO transactionListRespDTO = transactionService.getAccountTransactions(loginUser.getUser()
            .getId(), number, transactionType, page);

    return new ResponseEntity<>(new ResponseDTO<>(1, "입출금목록보기 성공", transactionListRespDTO), HttpStatus.OK);
    //return ResponseEntity.ok().body(new ResponseDTO<>(1, "입출금목록보기 성공", transactionListRespDTO));
  }
}
