package com.bidding.identity.domain.model;

/**
 * 地级行政区选项（直辖市 / 地级市 / 自治州 / 地区 / 盟等）。
 */
public class CityOption {

    private String code;
    private String name;
    private String province;
    private String provinceCode;

    public CityOption() {
    }

    public CityOption(String code, String name, String province, String provinceCode) {
        this.code = code;
        this.name = name;
        this.province = province;
        this.provinceCode = provinceCode;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getProvinceCode() {
        return provinceCode;
    }

    public void setProvinceCode(String provinceCode) {
        this.provinceCode = provinceCode;
    }
}
