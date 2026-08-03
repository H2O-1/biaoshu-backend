package com.bidding.identity.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;
import com.bidding.common.security.CurrentUser;
import com.bidding.common.security.UserContext;
import com.bidding.identity.domain.model.CityOption;
import com.bidding.identity.infrastructure.city.CityCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 城市选项（平台管理员创建组织时下拉选择）。
 */
@RestController
@RequestMapping("/api/admin/cities")
public class AdminCityController {

    private final CityCatalog cityCatalog;

    public AdminCityController(CityCatalog cityCatalog) {
        this.cityCatalog = cityCatalog;
    }

    /**
     * 查询全国地级市选项。
     * 前端可先拉省份再按省筛城市，或一次拉全量自行分组。
     */
    @GetMapping
    public ApiResponse<CityListResult> listCities(
            @RequestParam(required = false, defaultValue = "") String province,
            @RequestParam(required = false, defaultValue = "") String keyword) {
        checkPlatformAdmin();
        List<CityOption> items = cityCatalog.search(province, keyword);
        CityListResult result = new CityListResult();
        result.setItems(items);
        result.setTotal(items.size());
        return ApiResponse.ok(result);
    }

    /** 省份列表，便于前端省→市联动。 */
    @GetMapping("/provinces")
    public ApiResponse<ProvinceListResult> listProvinces() {
        checkPlatformAdmin();
        List<String> provinces = cityCatalog.listProvinces();
        ProvinceListResult result = new ProvinceListResult();
        result.setItems(provinces);
        result.setTotal(provinces.size());
        return ApiResponse.ok(result);
    }

    private void checkPlatformAdmin() {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isPlatformAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台管理员可操作");
        }
    }

    public static class CityListResult {
        private List<CityOption> items = new ArrayList<>();
        private int total;

        public List<CityOption> getItems() {
            return items;
        }

        public void setItems(List<CityOption> items) {
            this.items = items;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }
    }

    public static class ProvinceListResult {
        private List<String> items = new ArrayList<>();
        private int total;

        public List<String> getItems() {
            return items;
        }

        public void setItems(List<String> items) {
            this.items = items;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }
    }
}
