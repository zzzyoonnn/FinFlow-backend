package com.FinFlow.controller;

import com.FinFlow.config.auth.LoginUser;
import com.FinFlow.dto.ResponseDTO;
import com.FinFlow.dto.account.AccountReqDTO.AccountTransferReqDTO;
import com.FinFlow.dto.account.AccountReqDTO.AccountDepositReqDTO;
import com.FinFlow.dto.account.AccountReqDTO.AccountSaveReqDto;
import com.FinFlow.dto.account.AccountReqDTO.AccountWithdrawReqDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountDepositRespDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountDetailRespDto;
import com.FinFlow.dto.account.AccountRespDTO.AccountListRespDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountSaveRespDto;
import com.FinFlow.dto.account.AccountRespDTO.AccountTransferRespDTO;
import com.FinFlow.dto.account.AccountRespDTO.AccountWithdrawRespDTO;
import com.FinFlow.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountController {

  private final AccountService accountService;

  @PostMapping("/s/account")
  public ResponseEntity<?> saveAccount(@RequestBody @Valid AccountSaveReqDto accountSaveReqDto,
                                       BindingResult bindingResult, @AuthenticationPrincipal LoginUser loginUser) {

    AccountSaveRespDto accountSaveRespDto = accountService.registerAccount(accountSaveReqDto, loginUser.getUser()
            .getId());
    return new ResponseEntity<>(new ResponseDTO<>(1, "계좌등록 성공", accountSaveRespDto), HttpStatus.CREATED);
  }

  @GetMapping("/s/account/loginUser")
  public ResponseEntity<?> findUserAccount(@AuthenticationPrincipal LoginUser loginUser) {
    AccountListRespDTO accountListRespDTO = accountService.findAccountsByUser(loginUser.getUser().getId());

    return new ResponseEntity<>(new ResponseDTO<>(1, "계좌 목록 보기(유저별) 성공", accountListRespDTO), HttpStatus.OK);
  }

  @DeleteMapping("/s/account/{number}")
  public ResponseEntity<?> deleteAccount(@PathVariable("number") String number, @AuthenticationPrincipal LoginUser loginUser) {
    accountService.deleteAccount(number, loginUser.getUser().getId());

    return new ResponseEntity<>(new ResponseDTO<>(1, "계좌 삭제 완료", null), HttpStatus.OK);
  }

  @PostMapping("/account/deposit")
  public ResponseEntity<?> depositAccount(@RequestBody @Valid AccountDepositReqDTO accountDepositReqDTO, BindingResult bindingResult) {
    AccountDepositRespDTO accountDepositRespDTO = accountService.depositAccount(accountDepositReqDTO);

    return new ResponseEntity<>(new ResponseDTO<>(1, "계좌 입금 완료", accountDepositRespDTO), HttpStatus.CREATED);
  }

  @PostMapping("/s/account/withdraw")
  public ResponseEntity<?> withdrawAccount(@RequestBody @Valid AccountWithdrawReqDTO accountWithdrawReqDTO, BindingResult bindingResult, @AuthenticationPrincipal LoginUser loginUser) {
    AccountWithdrawRespDTO accountWithdrawRespDTO = accountService.withdrawAccount(accountWithdrawReqDTO, loginUser.getUser().getId());

    return new ResponseEntity<>(new ResponseDTO<>(1, "계좌 출금 완료", accountWithdrawRespDTO), HttpStatus.CREATED);
  }

  @PostMapping("/s/account/transfer")
  public ResponseEntity<?> transferAccount(@RequestBody @Valid AccountTransferReqDTO accountTransferReqDTO, BindingResult bindingResult, @AuthenticationPrincipal LoginUser loginUser) {
    AccountTransferRespDTO accountTransferRespDTO = accountService.transferAccount(accountTransferReqDTO, loginUser.getUser().getId());

    return new ResponseEntity<>(new ResponseDTO<>(1, "계좌 이체 완료", accountTransferRespDTO), HttpStatus.CREATED);
  }

  @GetMapping("/s/account/{number}")
  public ResponseEntity<?> findDetailAccount(@PathVariable String number,
                                             @RequestParam(value = "page", defaultValue = "0") Integer page,
                                             @AuthenticationPrincipal LoginUser loginUser) {
    AccountDetailRespDto accountDetailRespDto = accountService.findAccountById(number, loginUser.getUser().getId(),
            page);
    return new ResponseEntity<>(new ResponseDTO<>(1, "계좌상세보기 성공", accountDetailRespDto), HttpStatus.OK);
  }
}
