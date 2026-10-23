package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.security.SecurityUtils;
import io.tieringkv.webconsole.web.dto.UserCreateRequest;
import io.tieringkv.webconsole.web.dto.UserUpdateRequest;
import io.tieringkv.webconsole.web.dto.UserView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 用户管理（仅 ADMIN）。 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public PageResult<UserView> page(String keyword, int page, int size) {
        PageRequest request = PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, size)));
        Page<User> result = keyword == null || keyword.isBlank()
                ? userRepository.findAll(request)
                : userRepository.findByUsernameContainingOrderByIdAsc(keyword.trim(), request);
        List<UserView> items = result.getContent().stream().map(UserView::of).toList();
        return PageResult.of(items, result.getTotalElements(), page, size);
    }

    @Transactional
    public UserView create(UserCreateRequest request) {
        String username = request.username().trim();
        if (userRepository.existsByUsername(username)) {
            throw BusinessException.conflict("用户名已存在：" + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setEmail(request.email());
        user.setRole(request.role());
        user.setStatus(1);
        userRepository.save(user);
        auditService.record("USER_CREATE", username, "role=" + request.role(), true);
        return UserView.of(user);
    }

    @Transactional
    public UserView update(Long id, UserUpdateRequest request) {
        User user = find(id);
        if (request.nickname() != null) {
            user.setNickname(request.nickname());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.role() != null) {
            guardLastAdmin(user, request.role());
            user.setRole(request.role());
        }
        if (request.status() != null) {
            if (request.status() != 0 && request.status() != 1) {
                throw BusinessException.badRequest("状态取值只能为 0 或 1");
            }
            if (request.status() == 0) {
                guardLastAdmin(user, null);
                if (user.getId().equals(SecurityUtils.currentUser().id())) {
                    throw BusinessException.badRequest("不能禁用当前登录账号");
                }
            }
            user.setStatus(request.status());
        }
        userRepository.save(user);
        auditService.record("USER_UPDATE", user.getUsername(),
                "role=" + user.getRole() + ",status=" + user.getStatus(), true);
        return UserView.of(user);
    }

    @Transactional
    public void resetPassword(Long id, String rawPassword) {
        User user = find(id);
        user.setPassword(passwordEncoder.encode(rawPassword));
        userRepository.save(user);
        auditService.record("USER_PASSWORD_RESET", user.getUsername(), null, true);
    }

    @Transactional
    public void delete(Long id) {
        User user = find(id);
        if (user.getId().equals(SecurityUtils.currentUser().id())) {
            throw BusinessException.badRequest("不能删除当前登录账号");
        }
        guardLastAdmin(user, null);
        userRepository.delete(user);
        auditService.record("USER_DELETE", user.getUsername(), null, true);
    }

    private User find(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("用户不存在：" + id));
    }

    /** 保护最后一个 ADMIN 不被降级/禁用/删除。 */
    private void guardLastAdmin(User target, Role newRole) {
        if (target.getRole() != Role.ADMIN) {
            return;
        }
        boolean stillAdmin = newRole == Role.ADMIN;
        long admins = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.ADMIN && u.getStatus() == 1)
                .count();
        if (!stillAdmin && admins <= 1) {
            throw BusinessException.badRequest("系统必须保留至少一个启用的管理员");
        }
    }
}
