package com.shangpivot.customerservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.customerservice.entity.QuickReply;
import com.shangpivot.customerservice.entity.QuickReplyGroup;
import com.shangpivot.customerservice.mapper.QuickReplyGroupMapper;
import com.shangpivot.customerservice.mapper.QuickReplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 快捷回复：分组 + 话术维护（is_recommended=true 即 C 端推荐问）。 */
@Service
@RequiredArgsConstructor
public class QuickReplyAdminService {

    private final QuickReplyGroupMapper groupMapper;
    private final QuickReplyMapper replyMapper;

    public Map<String, Object> tree() {
        List<QuickReplyGroup> groups = groupMapper.selectList(new LambdaQueryWrapper<QuickReplyGroup>()
                .orderByAsc(QuickReplyGroup::getSort));
        List<Map<String, Object>> items = replyMapper.selectList(new LambdaQueryWrapper<QuickReply>()
                        .orderByAsc(QuickReply::getSort))
                .stream().map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", r.getId());
                    m.put("groupId", r.getGroupId());
                    m.put("title", r.getTitle());
                    m.put("content", r.getContent());
                    m.put("sort", r.getSort());
                    m.put("isRecommended", Boolean.TRUE.equals(r.getIsRecommended()));
                    return m;
                }).toList();
        Map<String, Object> tree = new HashMap<>();
        tree.put("groups", groups.stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("sort", g.getSort());
            return m;
        }).toList());
        tree.put("items", items);
        return tree;
    }

    public Long createGroup(String name, int sort) {
        if (!StringUtils.hasText(name) || name.length() > 50) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "分组名必填且 ≤50 字");
        }
        QuickReplyGroup g = new QuickReplyGroup();
        g.setName(name);
        g.setSort(sort);
        groupMapper.insert(g);
        return g.getId();
    }

    public void deleteGroup(Long id) {
        Long used = replyMapper.selectCount(new LambdaQueryWrapper<QuickReply>().eq(QuickReply::getGroupId, id));
        if (used > 0) {
            throw new BizException(ErrorCode.CONFLICT.getCode(), "分组下仍有话术，不能删除");
        }
        groupMapper.deleteById(id);
    }

    public Long createItem(Long groupId, String title, String content, int sort, boolean recommended) {
        validateItem(title, content);
        QuickReply r = new QuickReply();
        r.setGroupId(groupId);
        r.setTitle(title);
        r.setContent(content);
        r.setSort(sort);
        r.setIsRecommended(recommended);
        replyMapper.insert(r);
        return r.getId();
    }

    public void updateItem(Long id, Long groupId, String title, String content, int sort, boolean recommended) {
        requireItem(id);
        validateItem(title, content);
        QuickReply r = new QuickReply();
        r.setId(id);
        r.setGroupId(groupId);
        r.setTitle(title);
        r.setContent(content);
        r.setSort(sort);
        r.setIsRecommended(recommended);
        replyMapper.updateById(r);
    }

    public void deleteItem(Long id) {
        requireItem(id);
        replyMapper.deleteById(id);
    }

    private void requireItem(Long id) {
        QuickReply r = replyMapper.selectById(id);
        if (r == null || Boolean.TRUE.equals(r.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
    }

    private static void validateItem(String title, String content) {
        if (!StringUtils.hasText(title) || title.length() > 50) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "标题必填且 ≤50 字");
        }
        if (!StringUtils.hasText(content) || content.length() > 500) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "内容必填且 ≤500 字");
        }
    }
}
