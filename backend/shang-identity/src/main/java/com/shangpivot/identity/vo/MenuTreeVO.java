package com.shangpivot.identity.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MenuTreeVO {
    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private String type;
    private String path;
    private String component;
    private String icon;
    private Integer sort;
    private Boolean visible;
    private List<MenuTreeVO> children = new ArrayList<>();
}
