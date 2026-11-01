package com.shangpivot.common.security;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class LoginUser {
    private Long userId;
    private String username;
    private String clientType;
    private String jti;
    private Long deptId;
    private Set<String> permissions;
    private Set<String> roles;
}
