package com.sky.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.SetmealDish;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import io.swagger.models.auth.In;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * ClassName: DishServiceImpl
 *
 * @Author Mobai
 * @Create 2023/11/10 10:01
 * @Version 1.0
 * Description:
 */

@Slf4j
@Service
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private DishFlavorMapper dishFlavorMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private RedisTemplate redisTemplate;


    //同时操作两张表，添加事务
    @Transactional
    @Override
    public Result addDish(DishDTO dishDTO) {

        //属性对拷,排除flavors
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish, "flavors");

        //插入菜品
        dishMapper.insertDish(dish);

        //循环设置口味对应的菜品ID
        List<DishFlavor> flavors = dishDTO.getFlavors();
        flavors.forEach((dishFlavor) -> {
            dishFlavor.setDishId(dish.getId());
        });
        //批量插入口味数据
        dishFlavorMapper.batchInsert(flavors);

        //清理对应分类下的缓存数据
        String cacheName = "dish_" + dish.getCategoryId();
        redisTemplate.delete(cacheName);

        return Result.success("新增成功！");
    }


    @Override
    public PageResult getPage(DishPageQueryDTO dishPageQueryDTO) {

        //获取分页参数
        int page = dishPageQueryDTO.getPage();
        int pageSize = dishPageQueryDTO.getPageSize();

        //开启分页查询
        PageHelper.startPage(page, pageSize);

        //查询分页数据
        Page<DishVO> dishPage = dishMapper.getPage(dishPageQueryDTO);

        return new PageResult(dishPage.getTotal(), dishPage.getResult());
    }

    @Override
    @Transactional
    public Result batchRemove(List<Long> ids) {

        //判断菜品是否起售
        for (Long id : ids) {
            Integer count1 = dishMapper.queryStatusIsSell(id);
            if (count1 > 0) {
                return Result.error(MessageConstant.DISH_ON_SALE);
            }
        }

        //判断菜品是否关联了套餐
        for (Long id : ids) {
            Integer count2 = setmealDishMapper.queryDependSetmeal(id);
            if (count2 > 0) {
                return Result.error(MessageConstant.DISH_BE_RELATED_BY_SETMEAL);
            }
        }

        //批量移除redis缓存数据
        Set<Long> set = new HashSet<>();
        ids.forEach((id) -> {
            set.add(dishMapper.getById(id).getCategoryId());
        });
        Iterator<Long> iterator = set.iterator();
        while (iterator.hasNext()) {
            //移除缓存中的数据
            String cacheName = "dish_" + iterator.next();
            redisTemplate.delete(cacheName);
            log.info("移除缓存：" + cacheName);
        }

        for (Long id : ids) {
            //批量删除菜品
            dishMapper.removeDish(id);
            //批量删除菜品口味
            dishFlavorMapper.removeDishFlavor(id);
        }

        return Result.success("删除成功！");
    }


    @Override
    //TODO 后续需要在这里判断套餐状态，如果菜品在套餐当中，需要判断套餐状态是否起售
    public Result changeStatus(Integer status, Long id) {

        dishMapper.changeStatus(status, id);

        //清除缓存
        Long categoryId = dishMapper.getById(id).getCategoryId();
        String cacheName = "dish_" + categoryId;
        redisTemplate.delete(cacheName);
        log.info("清除缓存：" + cacheName);

        return Result.success("修改成功!");
    }

    @Override
    public Result<DishVO> getByDishId(Long id) {

        //获取菜品信息
        DishVO dishVO = dishMapper.getById(id);

        //获取口味数据
        List<DishFlavor> flavorList = dishFlavorMapper.getByDishId(id);

        //封装口味数据
        dishVO.setFlavors(flavorList);

        return Result.success(dishVO);
    }

    @Override
    @Transactional
    public Result modifyDish(DishDTO dishDTO) {

        //创建Dish对象，进行对象拷贝
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);

        //更新菜品数据(修改了菜品更新用户和时间)
        dishMapper.updateDish(dish);

        //更新口味数据
        List<DishFlavor> flavors = dishDTO.getFlavors();

        //删除原有口味数据
        dishFlavorMapper.removeDishFlavor(dish.getId());

        if (flavors != null && flavors.size() > 0) {

            //1.给菜品口味数据赋DishId
            flavors.forEach((flavor) -> {
                flavor.setDishId(dish.getId());
            });
            //2.新增菜品口味数据
            dishFlavorMapper.batchInsert(flavors);

        }

        //清除缓存
        Long categoryId = dish.getCategoryId();
        String cacheName = "dish_" + categoryId;
        redisTemplate.delete(cacheName);
        log.info("清除缓存:" + cacheName);

        return Result.success("修改成功!");
    }

    @Override
    public Result list(Dish dish) {

        List<Dish> dishList = dishMapper.list(dish);

        return Result.success(dishList);
    }

    /**
     * 条件查询菜品和口味
     *
     * @param dish
     * @return
     */
    public List<DishVO> listWithFlavor(Dish dish) {
        //判断id对应的分类是否存在Redis中
        String cacheName = "dish_" + dish.getCategoryId();
        //如果存在缓存中返回缓存数据
        if (redisTemplate.hasKey(cacheName) == true) {
            log.info("读取缓存:" + cacheName);
            String json = (String) redisTemplate.opsForValue().get(cacheName);
            List<DishVO> dishVOList = JSONObject.parseArray(json, DishVO.class);
            return dishVOList;
        }

        //不存在缓存中
        //查数据库
        List<Dish> dishList = dishMapper.list(dish);

        List<DishVO> dishVOList = new ArrayList<>();

        for (Dish d : dishList) {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(d, dishVO);

            //根据菜品id查询对应的口味
            List<DishFlavor> flavors = dishFlavorMapper.getByDishId(d.getId());

            dishVO.setFlavors(flavors);
            dishVOList.add(dishVO);
        }

        //存入缓存中
        redisTemplate.opsForValue().set(cacheName, JSONObject.toJSONString(dishVOList));

        return dishVOList;
    }
}
