package com.bidding.identity.infrastructure.city;

import com.bidding.identity.domain.model.CityOption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 全国地级市静态词典：启动时从 classpath JSON 加载。
 * 用于创建组织时下拉选择与校验。
 */
@Component
public class CityCatalog {

    private static final String RESOURCE_PATH = "cities/prefecture-cities.json";

    private final ObjectMapper objectMapper;

    private List<CityOption> allCities = Collections.emptyList();
    private Set<String> nameSet = Collections.emptySet();
    private Map<String, List<CityOption>> byProvince = Collections.emptyMap();

    public CityCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
            try (InputStream in = resource.getInputStream()) {
                List<CityOption> loaded = objectMapper.readValue(
                        in, new TypeReference<List<CityOption>>() {
                        });
                if (loaded == null || loaded.isEmpty()) {
                    throw new IllegalStateException("城市词典为空: " + RESOURCE_PATH);
                }

                List<CityOption> cities = new ArrayList<>();
                Set<String> names = new HashSet<>();
                Map<String, List<CityOption>> provinceMap = new HashMap<>();

                for (int i = 0; i < loaded.size(); i++) {
                    CityOption item = loaded.get(i);
                    if (item == null || item.getName() == null || item.getName().trim().isEmpty()) {
                        throw new IllegalStateException("城市词典第 " + (i + 1) + " 条缺少 name");
                    }
                    String name = item.getName().trim();
                    String province = item.getProvince() == null ? "" : item.getProvince().trim();
                    CityOption option = new CityOption(
                            item.getCode() == null ? "" : item.getCode().trim(),
                            name,
                            province,
                            item.getProvinceCode() == null ? "" : item.getProvinceCode().trim()
                    );
                    cities.add(option);
                    names.add(name);

                    List<CityOption> group = provinceMap.get(province);
                    if (group == null) {
                        group = new ArrayList<>();
                        provinceMap.put(province, group);
                    }
                    group.add(option);
                }

                this.allCities = Collections.unmodifiableList(cities);
                this.nameSet = Collections.unmodifiableSet(names);
                this.byProvince = Collections.unmodifiableMap(provinceMap);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("加载城市词典失败: " + RESOURCE_PATH, ex);
        }
    }

    /** 城市正式名是否在词典中（精确匹配） */
    public boolean containsName(String cityName) {
        if (cityName == null) {
            return false;
        }
        return nameSet.contains(cityName.trim());
    }

    /**
     * 查询城市选项。
     * province 为空则不限省；keyword 按城市名模糊匹配。
     */
    public List<CityOption> search(String province, String keyword) {
        String provinceKw = province == null ? "" : province.trim();
        String nameKw = keyword == null ? "" : keyword.trim();

        List<CityOption> source;
        if (provinceKw.isEmpty()) {
            source = allCities;
        } else {
            List<CityOption> group = byProvince.get(provinceKw);
            if (group == null) {
                return Collections.emptyList();
            }
            source = group;
        }

        if (nameKw.isEmpty()) {
            return new ArrayList<>(source);
        }

        String lowerKw = nameKw.toLowerCase(Locale.ROOT);
        List<CityOption> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            CityOption option = source.get(i);
            String name = option.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(lowerKw)) {
                result.add(option);
            }
        }
        return result;
    }

    /** 省份列表（按词典出现顺序去重） */
    public List<String> listProvinces() {
        List<String> provinces = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < allCities.size(); i++) {
            String province = allCities.get(i).getProvince();
            if (province == null || province.isEmpty()) {
                continue;
            }
            if (seen.add(province)) {
                provinces.add(province);
            }
        }
        return provinces;
    }

    public int size() {
        return allCities.size();
    }
}
