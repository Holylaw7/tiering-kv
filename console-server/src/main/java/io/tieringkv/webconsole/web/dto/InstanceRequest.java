package io.tieringkv.webconsole.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 新建/更新实例请求。 */
public record InstanceRequest(
        @NotBlank(message = "实例名称不能为空")
        @Size(max = 64, message = "实例名称过长")
        String name,
        @NotBlank(message = "主机不能为空")
        @Size(max = 128, message = "主机过长")
        String host,
        @Min(value = 1, message = "端口最小值 1")
        @Max(value = 65535, message = "端口最大值 65535")
        int port,
        @Size(max = 128, message = "口令过长")
        String password,
        @Size(max = 255, message = "备注过长")
        String remark,
        Boolean enabled) {
}
