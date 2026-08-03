package com.bidding.common.security;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.port.PackageLookupPort;
import com.bidding.common.port.ProjectLookupPort;

/**
 * 招标方业务鉴权小工具：登录校验、TENDERER 角色、本组织项目/标包归属。
 * <p>
 * 跨组织一律按「不存在」返回，避免探 ID。
 */
public final class TendererAuth {

    private TendererAuth() {
    }

    /**
     * 要求已登录且为招标方；默认文案「仅招标方可操作」。
     */
    public static CurrentUser requireTenderer() {
        return requireTenderer("仅招标方可操作");
    }

    /**
     * 要求已登录且为招标方。
     *
     * @param forbiddenMessage 非招标方时的 403 文案
     */
    public static CurrentUser requireTenderer(String forbiddenMessage) {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isTenderer()) {
            String msg = forbiddenMessage;
            if (msg == null || msg.isBlank()) {
                msg = "仅招标方可操作";
            }
            throw new BizException(ErrorCode.FORBIDDEN, msg);
        }
        return user;
    }

    /**
     * 要求项目存在、未删（由 Port 保证）、且属于 orgId。
     */
    public static ProjectLookupPort.ProjectRef requireOwnedProject(
            ProjectLookupPort projectLookupPort,
            long projectId,
            long orgId) {
        if (projectLookupPort == null) {
            throw new IllegalArgumentException("projectLookupPort 不能为空");
        }
        ProjectLookupPort.ProjectRef project = projectLookupPort.findActiveById(projectId);
        if (project == null || project.getOrgId() != orgId) {
            throw new BizException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return project;
    }

    /**
     * 要求标包存在、未删（由 Port 保证）、且属于 orgId。
     */
    public static PackageLookupPort.PackageRef requireOwnedPackage(
            PackageLookupPort packageLookupPort,
            long packageId,
            long orgId) {
        if (packageLookupPort == null) {
            throw new IllegalArgumentException("packageLookupPort 不能为空");
        }
        PackageLookupPort.PackageRef pkg = packageLookupPort.findActiveById(packageId);
        if (pkg == null || pkg.getOrgId() != orgId) {
            throw new BizException(ErrorCode.NOT_FOUND, "标包不存在");
        }
        return pkg;
    }
}
