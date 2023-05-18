package com.gavoyage.config.jwt.filter;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gavoyage.config.jwt.JwtProperties;
import com.gavoyage.config.jwt.service.JwtService;
import com.gavoyage.config.login.PrincipalDetails;
import com.gavoyage.user.domain.Users;
import com.gavoyage.user.service.UserServiceImpl;

import lombok.extern.slf4j.Slf4j;


// 권한이나 인증이 필요한(회원용 api) url을 호출했을 경우  스프링 시큐리티의 BasicAuthenticationFilter를 무조건 거치게 된다.
// 권한이나 인증이 필요 없는 경우에는 거치지 않는다.

/**
 * 사용자가 인증을 요청하는 형태는 총 세가지로 구분된다.(사용자는 요청 시 access token 또는 refresh token 둘 중 하나만 보낸다)
 * 1. Access Token이 유효한 채로 존재하는 경우 => 인증 성공 
 * 2. Access Token이 없거나 유효하지 않은 채로 존재하는 경우 => 인증 실패
 * 3. Refresh Token이 있는 경우
 *  => 로그인한 사용자의 refresh token과 비교하여 일치한다면 Access Token과 Refresh Token 모두 재발급(RTR) + 인증 실패
 */
@Slf4j
public class JwtAuthorizationFilter extends BasicAuthenticationFilter {
	
	private final JwtService jwtService;
	private final UserServiceImpl userService;
	
//	public JwtAuthorizationFilter(JwtService jwtService, UserServiceImpl userService) {
//		super();
//		this.jwtService = jwtService;
//		this.userService = userService;
//	}

	public JwtAuthorizationFilter(AuthenticationManager authenticationManager, JwtService jwtService, UserServiceImpl userService) {
		super(authenticationManager);
		this.userService = userService;
		this.jwtService = jwtService;
	}
	
	// 인증이나 권한 요청이 있을 경우 호출되는 메소드
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		
		log.debug("인증이나 권한이 필요한 주소 요청");
		log.debug("request.getRequestURI() : " + request.getRequestURL());
		
		
		if(request.getRequestURI().equals("/login")) { // "/login" url로 요청시 인증 과정 생략
			log.debug("인증인데 login 주소로 온 경우");
			chain.doFilter(request, response);
			return;
		}
		
		String accessToken = jwtService.extractAccessToken(request).orElse(null);
		String refreshToken = jwtService.extractRefreshToken(request).orElse(null);
		
		log.debug("accessToken : " + refreshToken);
		log.debug("refreshToken : " + refreshToken);
		
		if((accessToken == null && refreshToken == null)) { // header가 없거나 Bearer 방식이 아닐 경우
			log.debug("토큰이 아예 없는 경우");
			chain.doFilter(request, response);
			return;
		}
		
		
		// refresh token이 만료된 경우 에러를 발생 시키고 access token과 refresh token 모두 재발급
		if(refreshToken != null) {
			log.debug("refresh token이 만료된 경우");
			
			Users findUser = userService.findByRefreshToken(refreshToken);
			log.debug("findUser : " + findUser);
			
			// refresh token 갱신
			String reIssuedRefreshToken = jwtService.createRefreshToken();
			userService.updateRefreshToken(findUser.getEmail(), reIssuedRefreshToken);
			
			jwtService.sendAccessAndRefreshToken(response,
								jwtService.createAccessToken(findUser),
								reIssuedRefreshToken);
		}
		
		// access token을 보내 인증 처리
		if(refreshToken == null) {
			log.debug("유효한 토큰인 경우");
			
			if(!jwtService.isTokenValid(accessToken)) {
				throw new ServletException("유효하지 않은 토큰입니다.");
			}
			
			String email = jwtService.extractEmail(accessToken).orElse(null); // claim으로 부터 email 추출
			Users userEntity = userService.findByUserEmail(email).orElse(null);
			
			PrincipalDetails principalDetails = new PrincipalDetails(userEntity);
			log.debug("userEntity : " + userEntity);
			
			// JWT 토큰 서명이 정상일 경우 Authentication 객체를 직접 생성한다.
			Authentication authentication = 
					new UsernamePasswordAuthenticationToken(principalDetails, null, principalDetails.getAuthorities());
			
			// 위에서 만든 Authentication을 스프링 세큐리티의 세션에 강제로 저장
			SecurityContextHolder.getContext().setAuthentication(authentication);
			
			chain.doFilter(request, response);
		}
		
	}
}