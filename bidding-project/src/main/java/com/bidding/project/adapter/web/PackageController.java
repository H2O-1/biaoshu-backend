package com.bidding.project.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.api.PageResult;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.project.application.PackageAppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 标包接口（招标方）。
 * 招标包 / 投标包创建时均不绑项目；项目侧再绑定。
 */
@RestController
@RequestMapping("/api/packages")
public class PackageController {

    private final PackageAppService packageAppService;

    public PackageController(PackageAppService packageAppService) {
        this.packageAppService = packageAppService;
    }

    /**
     * 创建标包。
     * type 必填；name / contact 可空；name 空则自动生成。
     */
    @PostMapping
    public ApiResponse<PackageAppService.PackageSummary> create(
            @Valid @RequestBody CreatePackageRequest request) {
        String type = normalizeType(request.getType());
        if (PackageAppService.TYPE_TENDER.equals(type)) {
            return ApiResponse.ok(packageAppService.createTender(request.getName()));
        }
        if (PackageAppService.TYPE_BID.equals(type)) {
            return ApiResponse.ok(packageAppService.createBid(
                    request.getName(),
                    request.getContactName(),
                    request.getContactPhone(),
                    request.getCompanyAddress(),
                    request.getPriceWithTax(),
                    request.getTaxRate(),
                    request.getPriceWithoutTax()
            ));
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "type 仅支持 TENDER 或 BID");
    }

    /**
     * 标包列表。unboundOnly=true 时只返回未绑定项目的。
     */
    @GetMapping
    public ApiResponse<PageResult<PackageAppService.PackageSummary>> list(
            @RequestParam(required = false, defaultValue = "TENDER") String type,
            @RequestParam(required = false, defaultValue = "") String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean unboundOnly,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ApiResponse.ok(packageAppService.list(type, keyword, unboundOnly, page, size));
    }

    @GetMapping("/{packageId}")
    public ApiResponse<PackageAppService.PackageSummary> get(
            @PathVariable("packageId") long packageId) {
        return ApiResponse.ok(packageAppService.get(packageId));
    }

    @PutMapping("/{packageId}")
    public ApiResponse<PackageAppService.PackageSummary> update(
            @PathVariable("packageId") long packageId,
            @Valid @RequestBody UpdatePackageRequest request) {
        PackageAppService.PackageSummary current = packageAppService.get(packageId);
        if (PackageAppService.TYPE_TENDER.equals(current.getType())) {
            return ApiResponse.ok(packageAppService.updateTenderName(packageId, request.getName()));
        }
        if (PackageAppService.TYPE_BID.equals(current.getType())) {
            return ApiResponse.ok(packageAppService.updateBid(
                    packageId,
                    request.getName(),
                    request.getContactName(),
                    request.getContactPhone(),
                    request.getCompanyAddress(),
                    request.getPriceWithTax(),
                    request.getTaxRate(),
                    request.getPriceWithoutTax()
            ));
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "不支持的标包类型");
    }

    @DeleteMapping("/{packageId}")
    public ApiResponse<Void> delete(@PathVariable("packageId") long packageId) {
        packageAppService.delete(packageId);
        return ApiResponse.ok();
    }

    private String normalizeType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "type 不能为空");
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }

    public static class CreatePackageRequest {
        @NotBlank
        private String type;

        @Size(max = 128)
        private String name;

        /** 投标包可选：负责人姓名 */
        @Size(max = 64)
        private String contactName;

        /** 投标包可选：负责人电话 */
        @Size(max = 32)
        private String contactPhone;

        /** 投标包可选：公司地址 */
        @Size(max = 256)
        private String companyAddress;

        /** 投标包可选：含税价格（前端传入） */
        private BigDecimal priceWithTax;

        /** 投标包可选：税率，如 13 表示 13% */
        private BigDecimal taxRate;

        /** 投标包可选：不含税价（前端计算后传入） */
        private BigDecimal priceWithoutTax;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getContactName() {
            return contactName;
        }

        public void setContactName(String contactName) {
            this.contactName = contactName;
        }

        public String getContactPhone() {
            return contactPhone;
        }

        public void setContactPhone(String contactPhone) {
            this.contactPhone = contactPhone;
        }

        public String getCompanyAddress() {
            return companyAddress;
        }

        public void setCompanyAddress(String companyAddress) {
            this.companyAddress = companyAddress;
        }

        public BigDecimal getPriceWithTax() {
            return priceWithTax;
        }

        public void setPriceWithTax(BigDecimal priceWithTax) {
            this.priceWithTax = priceWithTax;
        }

        public BigDecimal getTaxRate() {
            return taxRate;
        }

        public void setTaxRate(BigDecimal taxRate) {
            this.taxRate = taxRate;
        }

        public BigDecimal getPriceWithoutTax() {
            return priceWithoutTax;
        }

        public void setPriceWithoutTax(BigDecimal priceWithoutTax) {
            this.priceWithoutTax = priceWithoutTax;
        }
    }

    public static class UpdatePackageRequest {
        @NotBlank
        @Size(max = 128)
        private String name;

        @Size(max = 64)
        private String contactName;

        @Size(max = 32)
        private String contactPhone;

        @Size(max = 256)
        private String companyAddress;

        private BigDecimal priceWithTax;

        private BigDecimal taxRate;

        private BigDecimal priceWithoutTax;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getContactName() {
            return contactName;
        }

        public void setContactName(String contactName) {
            this.contactName = contactName;
        }

        public String getContactPhone() {
            return contactPhone;
        }

        public void setContactPhone(String contactPhone) {
            this.contactPhone = contactPhone;
        }

        public String getCompanyAddress() {
            return companyAddress;
        }

        public void setCompanyAddress(String companyAddress) {
            this.companyAddress = companyAddress;
        }

        public BigDecimal getPriceWithTax() {
            return priceWithTax;
        }

        public void setPriceWithTax(BigDecimal priceWithTax) {
            this.priceWithTax = priceWithTax;
        }

        public BigDecimal getTaxRate() {
            return taxRate;
        }

        public void setTaxRate(BigDecimal taxRate) {
            this.taxRate = taxRate;
        }

        public BigDecimal getPriceWithoutTax() {
            return priceWithoutTax;
        }

        public void setPriceWithoutTax(BigDecimal priceWithoutTax) {
            this.priceWithoutTax = priceWithoutTax;
        }
    }
}
