package com.bidding.identity.infrastructure.city;

import com.bidding.identity.domain.model.CityOption;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 城市词典加载与查询。
 */
class CityCatalogTest {

    private CityCatalog cityCatalog;

    @BeforeEach
    void setUp() {
        cityCatalog = new CityCatalog(new ObjectMapper());
        cityCatalog.load();
    }

    @Test
    void loadContainsMajorCities() {
        assertTrue(cityCatalog.size() > 300);
        assertTrue(cityCatalog.containsName("北京市"));
        assertTrue(cityCatalog.containsName("深圳市"));
        assertTrue(cityCatalog.containsName("台北市"));
        assertTrue(cityCatalog.containsName("香港特别行政区"));
        assertTrue(cityCatalog.containsName("澳门特别行政区"));
        assertFalse(cityCatalog.containsName("深圳"));
        assertFalse(cityCatalog.containsName("不存在市"));
    }

    @Test
    void searchByProvinceAndKeyword() {
        List<CityOption> guangdong = cityCatalog.search("广东省", "");
        assertFalse(guangdong.isEmpty());

        List<CityOption> shenzhen = cityCatalog.search("广东省", "深圳");
        assertTrue(shenzhen.size() >= 1);
        assertTrue(shenzhen.get(0).getName().contains("深圳"));

        List<String> provinces = cityCatalog.listProvinces();
        assertTrue(provinces.contains("广东省"));
        assertTrue(provinces.contains("北京市"));
        assertTrue(provinces.contains("台湾省"));
        assertTrue(provinces.contains("香港特别行政区"));
        assertTrue(provinces.contains("澳门特别行政区"));
    }
}
