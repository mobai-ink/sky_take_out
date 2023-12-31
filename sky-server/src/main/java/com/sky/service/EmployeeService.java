package com.sky.service;

import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import com.sky.result.PageResult;
import com.sky.result.Result;

import java.util.Map;

public interface EmployeeService {

    /**
     * 员工登录
     *
     * @param employeeLoginDTO
     * @return
     */
    Employee login(EmployeeLoginDTO employeeLoginDTO);

    Result add(Employee employee);

    PageResult getPage(EmployeePageQueryDTO pageQueryDTO);

    Result changeStatus(int status, Long id);

    EmployeeDTO getEmpById(Long id);

    Result modifyEmp(EmployeeDTO employeeDTO);
}
