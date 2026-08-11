package org.qommons.misc.springdemo.repository;

import org.qommons.misc.springdemo.entities.DemoUser;
import org.springframework.data.repository.CrudRepository;

public interface UserRepository extends CrudRepository<DemoUser, String> {
}
