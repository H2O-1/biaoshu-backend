package com.bidding.common.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * 标包只读查询（供 file 等模块校验归属；实现在 project 模块）。
 */
public interface PackageLookupPort {

    /**
     * 按 ID 查未删除标包；不存在或已删除返回 null。
     */
    PackageRef findActiveById(long packageId);

    /**
     * 按项目 + 类型查未删除标包；不存在返回 null。
     *
     * @param type TENDER / BID
     */
    PackageRef findActiveByProjectIdAndType(long projectId, String type);

    /**
     * 列出项目下指定类型的未删除标包（投标包可多条）。
     */
    List<PackageRef> listActiveByProjectIdAndType(long projectId, String type);

    /** 跨模块传递的标包摘要（避免 file 依赖 project 模型）。 */
    class PackageRef {
        private long id;
        private long orgId;
        private Long projectId;
        private String type;
        private String status;
        private String name;
        private String contactName;
        private String contactPhone;
        private String companyAddress;
        private BigDecimal priceWithTax;
        private BigDecimal taxRate;
        private BigDecimal priceWithoutTax;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public long getOrgId() {
            return orgId;
        }

        public void setOrgId(long orgId) {
            this.orgId = orgId;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
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
}
