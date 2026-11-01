package com.aidianping.service.impl;

import com.aidianping.dto.LoginFormDTO;
import com.aidianping.dto.Result;
import com.aidianping.entity.User;
import com.aidianping.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserLoginRegressionTest {
    private final UserMapper mapper = mock(UserMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final UserServiceImpl service = new UserServiceImpl(mapper);
    private LoginFormDTO form;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(values.get("login:code:13800138000")).thenReturn("123456");
        form = new LoginFormDTO();
        form.setPhone("13800138000");
        form.setCode("123456");
    }

    @Test
    void firstLoginStoresGeneratedUserIdInTokenRecord() {
        doAnswer(invocation -> {
            invocation.<User>getArgument(0).setId(1001L);
            return null;
        }).when(mapper).save(any(User.class));
        Result result = service.login(form, null);
        assertTrue(result.getSuccess());
        verify(hashes).putAll(eq("login:token:" + result.getData()),
                argThat(fields -> "1001".equals(fields.get("id"))));
    }

    @Test
    void missingGeneratedIdMustNotIssueAnInvalidToken() {
        assertThrows(IllegalStateException.class, () -> service.login(form, null));
        verify(hashes, never()).putAll(anyString(), anyMap());
    }

    @Test
    void existingUserLoginDoesNotInsertAgain() {
        User user = new User();
        user.setId(42L);
        when(mapper.selectByPhone(form.getPhone())).thenReturn(user);
        assertTrue(service.login(form, null).getSuccess());
        verify(mapper, never()).save(any());
        verify(hashes).putAll(anyString(),
                argThat(fields -> "42".equals(fields.get("id"))));
    }
}
