package com.FinFlow.dto.user;

import com.FinFlow.domain.User;
import com.FinFlow.domain.UserEnum;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class UserReqDto {

  @Getter
  @Setter
  public static class LoginReqDto {
    private String username;
    private String password;
  }

  @Setter
  @Getter
  public static class JoinReqDto {

    // 영문, 숫자 가능
    @NotEmpty // null or blank space
    @Pattern(regexp = "^[a-zA-Z0-9]{2,20}$", message = "Please enter 2–20 characters using letters or digits.")
    private String username;

    // 길이 4~20
    @NotEmpty
    @Size(min = 4, max = 20, message = "Password must be 4–20 characters long.")
    private String password;

    // 이메일 형식
    @NotEmpty
    @Email(message = "Please enter a valid email address.")
    private String email;

    // 영어, 한글, 1~20
    @NotEmpty
    @Pattern(regexp = "^[a-zA-Z가-힣]{1,20}$", message = "Please enter 1–20 characters using Korean or English letters.")
    private String fullname;

    public User toEntity(BCryptPasswordEncoder bCryptPasswordEncoder) {
      return User.builder()
              .username(username)
              .password(bCryptPasswordEncoder.encode(password))
              .email(email)
              .fullname(fullname)
              .role(UserEnum.CUSTOMER)
              .build();
    }
  }
}
