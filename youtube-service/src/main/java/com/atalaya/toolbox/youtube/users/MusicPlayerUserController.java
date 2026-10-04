package com.atalaya.toolbox.youtube.users;

import com.atalaya.toolbox.youtube.users.domain.MusicPlayerUser;
import com.atalaya.toolbox.youtube.users.domain.MusicPlayerUserRequest;
import com.atalaya.toolbox.youtube.users.service.MusicPlayerUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/youtube/users")
public class MusicPlayerUserController {
    private final MusicPlayerUserService userService;

    public MusicPlayerUserController(MusicPlayerUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<MusicPlayerUser> listUsers() {
        return userService.listUsers();
    }

    @PostMapping
    public MusicPlayerUser selectOrCreate(@Valid @RequestBody MusicPlayerUserRequest request) {
        return userService.selectOrCreate(request.username());
    }
}
