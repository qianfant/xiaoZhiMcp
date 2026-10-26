package me.xiaozhi.mcp.account;

import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("success", false, "message", ex.getReason() == null ? "请求失败" : ex.getReason()));
    }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "参数格式或取值无效"));
    }
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate() {
        return ResponseEntity.status(409).body(Map.of("success", false, "message", "名称已存在"));
    }
}
