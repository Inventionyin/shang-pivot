package com.shangpivot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
		scanBasePackages = "com.shangpivot",
		// 使用自建 JWT 登录，排除默认生成的 user/随机密码
		exclude = UserDetailsServiceAutoConfiguration.class
)
@MapperScan("com.shangpivot.**.mapper")
@EnableScheduling
public class ShangPivotApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShangPivotApplication.class, args);
	}

}
