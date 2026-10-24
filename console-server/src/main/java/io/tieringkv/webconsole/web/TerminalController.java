package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.domain.TerminalHistory;
import io.tieringkv.webconsole.service.TerminalService;
import io.tieringkv.webconsole.web.dto.TerminalRequest;
import io.tieringkv.webconsole.web.dto.TerminalResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 在线终端接口。 */
@RestController
@RequestMapping("/api/v1/terminal")
public class TerminalController {

    private final TerminalService terminalService;

    public TerminalController(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @PostMapping("/execute")
    public ApiResponse<TerminalResult> execute(@Valid @RequestBody TerminalRequest request) {
        return ApiResponse.ok(terminalService.execute(request.instanceId(), request.command()));
    }

    @GetMapping("/history")
    public ApiResponse<PageResult<TerminalHistory>> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(terminalService.history(page, size));
    }
}
